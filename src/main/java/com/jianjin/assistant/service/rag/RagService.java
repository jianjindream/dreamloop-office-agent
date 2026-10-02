package com.jianjin.assistant.service.rag;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.domain.rag.HistoryMessage;
import com.jianjin.assistant.domain.rag.QuerySpec;
import com.jianjin.assistant.domain.rag.QueryType;
import com.jianjin.assistant.domain.rag.Reranker;
import com.jianjin.assistant.domain.rag.Rewriter;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.infrastructure.persistence.RagTenantRepository;
import com.jianjin.assistant.model.Chunk;
import com.jianjin.assistant.service.graph.ChunkRef;
import com.jianjin.assistant.service.graph.KGStore;
import com.jianjin.assistant.service.memory.LongTermMemory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final AppConfig cfg;
    private final HybridStore store;
    private final TextSplitter splitter;
    private final InfrastructureService infra;
    private KGStore kg;

    private boolean loaded = false;
    private BiFunction<String, String, String> generateFn;
    private Function<String, List<Double>> embedFn;
    private Rewriter rewriter;
    private Reranker reranker;
    private RagTenantRepository tenantRepository;
    private final ExecutorService expansionExecutor;

    public RagService(AppConfig cfg, HybridStore store, TextSplitter splitter, InfrastructureService infra) {
        this.cfg = cfg;
        this.store = store;
        this.splitter = splitter;
        this.infra = infra;
        this.splitter.setChunkSize(cfg.getRag().getChunkSize());
        this.splitter.setOverlap(cfg.getRag().getChunkOverlap());
        this.splitter.setParentChunkSize(cfg.getRag().getParentChunkSize() > 0
                ? cfg.getRag().getParentChunkSize()
                : Math.max(cfg.getRag().getChunkSize() * 4, 600));
        this.splitter.setParentOverlap(cfg.getRag().getParentChunkOverlap() > 0
                ? cfg.getRag().getParentChunkOverlap()
                : cfg.getRag().getChunkOverlap() * 2);
        int parallelism = Math.min(2, Math.max(1, cfg.getRag().getRewrite().getParallelism()));
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "rag-query-expand");
            thread.setDaemon(true);
            return thread;
        };
        this.expansionExecutor = Executors.newFixedThreadPool(parallelism, threadFactory);
    }

    public void setGenerateFn(BiFunction<String, String, String> fn) { this.generateFn = fn; }
    public void setEmbedFn(Function<String, List<Double>> fn) {
        this.embedFn = fn;
        this.store.setEmbedFn(fn);
    }
    public void setKGStore(KGStore kg) {
        this.kg = kg;
        this.store.setKGStore(kg);
    }
    /** 注入查询改写器；null 等价于关闭 */
    public void setRewriter(Rewriter r) { this.rewriter = r; }
    /** 注入精排器；null 等价于关闭 */
    public void setReranker(Reranker r) { this.reranker = r; }
    @Autowired(required = false)
    public void setTenantRepository(RagTenantRepository tenantRepository) { this.tenantRepository = tenantRepository; }

    public boolean isLoaded() { return loaded; }
    public String getMode() { return store.getMode(); }

    @PreDestroy
    public void close() {
        expansionExecutor.shutdownNow();
    }

    public Map.Entry<Integer, String> ingest(String doc) {
        return ingest("default:default", doc);
    }

    public Map.Entry<Integer, String> ingest(String ownerKey, String doc) {
        List<TextSplitter.ParentChunk> parents = splitter.splitParentChild(doc);
        // Include the owner in the hash input so identical files in two workspaces never share ownership.
        HybridStore.IndexResult indexed = store.index(parents, ownerKey + "\n" + doc);
        if (tenantRepository != null) tenantRepository.assign(indexed.docHash, ownerKey);
        loaded = true;
        infra.publishEvent("rag.ingest",
                String.format("{\"chunk_count\":%d,\"mode\":\"%s\",\"doc_hash\":\"%s\"}",
                        indexed.childRefs.size(), store.getMode(), indexed.docHash));
        // 异步建图
        if (kg != null && kg.available()) {
            new Thread(() -> kg.indexDocument(indexed.docHash, indexed.childRefs), "kg-index").start();
        }
        return Map.entry(indexed.childRefs.size(), indexed.docHash);
    }

    public void delete(String docHash) {
        delete("default:default", docHash);
    }

    public void delete(String ownerKey, String docHash) {
        if (tenantRepository != null && !tenantRepository.owns(docHash, ownerKey)) {
            throw new IllegalArgumentException("document index not found: " + docHash);
        }
        store.delete(docHash);
        if (tenantRepository != null) tenantRepository.remove(docHash);
        if (kg != null && kg.available()) kg.deleteDocument(docHash);
        // 重新检测是否还有 chunks
        loaded = !infra.loadAllRAGChunks().isEmpty();
    }

    public QueryResult query(String question) {
        return queryWithHistory(question, null);
    }

    public QueryResult queryWithHistory(String question, List<HistoryMessage> history) {
        QueryTrace trace = traceQueryWithHistory(null, question, history);
        return new QueryResult(trace.answer, trace.finalContexts);
    }

    public QueryResult queryWithHistory(String ownerKey, String question, List<HistoryMessage> history) {
        QueryTrace trace = traceQueryWithHistory(ownerKey, question, history);
        return new QueryResult(trace.answer, trace.finalContexts);
    }

    /**
     * Runs the same production RAG pipeline as {@link #queryWithHistory}, but retains
     * stable parent-context IDs and every ranking stage for offline evaluation.
     */
    public QueryTrace traceQuery(String question) {
        return traceQueryWithHistory(question, null);
    }

    public QueryTrace traceQueryWithHistory(String question, List<HistoryMessage> history) {
        return traceQueryWithHistory(null, question, history);
    }

    private QueryTrace traceQueryWithHistory(String ownerKey, String question, List<HistoryMessage> history) {
        QueryTrace trace = new QueryTrace();
        trace.originalQuestion = question;
        trace.mode = store.getMode();
        if (!loaded) {
            trace.answer = "知识库为空，请先上传文档。";
            trace.warning = "knowledge_base_empty";
            return trace;
        }

        List<HistoryMessage> safeHistory = history == null ? Collections.emptyList() : history;
        QuerySpec primary = rewriter == null
                ? new QuerySpec(question, QueryType.PRIMARY)
                : rewriter.rewritePrimary(question, safeHistory);
        if (primary.text().isBlank()) primary = new QuerySpec(question, QueryType.PRIMARY);
        trace.primaryQuery = primary.text();

        // Fetch extra parent contexts only when reranking is enabled.
        int fetchK = ownerKey == null
                ? (reranker != null ? Math.max(cfg.getRag().getTopK() * 4, 10) : cfg.getRag().getTopK())
                : Math.max(100, cfg.getRag().getTopK() * 8);
        List<HybridStore.SearchResult> primaryHits = filterOwned(ownerKey, store.search(primary.text(), fetchK));
        List<ScoredChunk> results = toScored(primaryHits);
        trace.primaryRetrievalCandidates = copy(results);
        if (shouldExpand(primaryHits) && rewriter != null) {
            List<QuerySpec> variants = rewriter.expand(primary.text(), safeHistory);
            if (variants != null && !variants.isEmpty()) {
                for (QuerySpec variant : variants) trace.expandedQueries.add(variant.text());
                results = mergePrimaryAndVariants(primary, primaryHits, variants, fetchK, ownerKey);
            }
        }

        // unavailable 模式：兜底 TF
        if (results.isEmpty() && "unavailable".equals(store.getMode())) {
            results = filterOwnedScored(ownerKey, tfSearch(question, fetchK));
            trace.fallbackUsed = true;
        }
        if (results.isEmpty()) {
            trace.answer = "知识库中未找到相关内容。";
            trace.warning = "no_retrieval_results";
            return trace;
        }

        trace.retrievalCandidates = copy(results);

        // 3) Rerank
        if (reranker != null) {
            results = reranker.rerank(question, results, cfg.getRag().getTopK());
        } else if (results.size() > cfg.getRag().getTopK()) {
            results = new ArrayList<>(results.subList(0, cfg.getRag().getTopK()));
        }
        trace.rerankedContexts = copy(results);
        trace.finalContexts = copy(results);

        String context = results.stream()
                .map(r -> r.chunk.getContent())
                .collect(Collectors.joining("\n\n"));

        String answer;
        if (generateFn != null) {
            String systemPrompt = "你是一个基于知识库回答问题的助手。请仅根据提供的上下文内容回答问题，不要编造信息。如果上下文不足以回答，请说明。";
            String userMsg = String.format("上下文：\n%s\n\n问题：%s", context, primary.text());
            answer = generateFn.apply(systemPrompt, userMsg);
        } else {
            answer = "【知识库检索结果】\n" + context;
        }
        trace.answer = answer;
        trace.mode = store.getMode();
        return trace;
    }

    public List<ScoredChunk> searchMulti(List<String> queries, int topK) {
        if (queries == null || queries.isEmpty()) return Collections.emptyList();
        QuerySpec primary = new QuerySpec(queries.get(0), QueryType.PRIMARY);
        List<HybridStore.SearchResult> primaryHits = store.search(primary.text(), topK);
        List<QuerySpec> variants = new ArrayList<>();
        for (int i = 1; i < queries.size(); i++) variants.add(new QuerySpec(queries.get(i), QueryType.VARIANT));
        return variants.isEmpty() ? toScored(primaryHits) : mergePrimaryAndVariants(primary, primaryHits, variants, topK, null);
    }

    private List<HybridStore.SearchResult> filterOwned(String ownerKey, List<HybridStore.SearchResult> results) {
        if (ownerKey == null || tenantRepository == null || results == null || results.isEmpty()) return results;
        Set<Long> allowed = tenantRepository.allowedContexts(ownerKey,
                results.stream().map(item -> item.contextId).toList());
        return results.stream().filter(item -> allowed.contains(item.contextId)).toList();
    }

    private List<ScoredChunk> filterOwnedScored(String ownerKey, List<ScoredChunk> results) {
        if (ownerKey == null || tenantRepository == null || results == null || results.isEmpty()) return results;
        Set<Long> allowed = tenantRepository.allowedContexts(ownerKey,
                results.stream().map(item -> item.contextId).toList());
        return results.stream().filter(item -> allowed.contains(item.contextId)).toList();
    }

    private boolean shouldExpand(List<HybridStore.SearchResult> primaryHits) {
        if (cfg.getRag().getRewrite().getNumQueries() <= 1 || "unavailable".equals(store.getMode())) return false;
        if (primaryHits == null || primaryHits.size() < cfg.getRag().getTopK()) return true;
        if (!"hybrid".equals(store.getMode())) return false;
        return primaryHits.stream().noneMatch(hit -> hit.sourceSupportCount >= 2);
    }

    private List<ScoredChunk> mergePrimaryAndVariants(QuerySpec primary,
                                                       List<HybridStore.SearchResult> primaryHits,
                                                       List<QuerySpec> variants,
                                                       int topK,
                                                       String ownerKey) {
        List<QueryHits> all = new ArrayList<>();
        all.add(new QueryHits(primary, primaryHits));
        List<CompletableFuture<QueryHits>> futures = new ArrayList<>();
        for (QuerySpec variant : variants) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return new QueryHits(variant, filterOwned(ownerKey, store.search(variant.text(), topK)));
                } catch (Exception e) {
                    log.warn("Expanded query search failed; skipping variant: {}", e.getMessage());
                    return new QueryHits(variant, Collections.emptyList());
                }
            }, expansionExecutor));
        }
        for (CompletableFuture<QueryHits> future : futures) {
            try {
                all.add(future.join());
            } catch (Exception e) {
                log.warn("Expanded query task failed; skipping variant: {}", e.getMessage());
            }
        }
        return fuseQueryHits(all, topK);
    }

    private List<ScoredChunk> fuseQueryHits(List<QueryHits> queryHits, int topK) {
        List<QueryHits> active = queryHits.stream()
                .filter(item -> item.hits != null && !item.hits.isEmpty())
                .toList();
        if (active.isEmpty()) return Collections.emptyList();
        if (active.size() == 1) return toScored(active.get(0).hits);

        Map<Long, FusedContext> rrf = new LinkedHashMap<>();
        double totalWeight = 0;
        int k = cfg.getRag().getRrfConstantK() > 0 ? cfg.getRag().getRrfConstantK() : 60;
        for (QueryHits query : active) {
            double weight = queryWeight(query.spec.type());
            totalWeight += weight;
            for (int i = 0; i < query.hits.size(); i++) {
                HybridStore.SearchResult hit = query.hits.get(i);
                if (hit == null || hit.chunk == null) continue;
                FusedContext context = rrf.computeIfAbsent(hit.contextId,
                        ignored -> new FusedContext(hit.contextId, hit.chunk, "query-rrf", hit.sourceSupportCount));
                context.score += weight / (k + i + 1);
            }
        }
        if (rrf.isEmpty() || totalWeight <= 0) return Collections.emptyList();

        List<Map.Entry<Long, FusedContext>> sorted = new ArrayList<>(rrf.entrySet());
        sorted.sort((left, right) -> Double.compare(right.getValue().score, left.getValue().score));
        List<ScoredChunk> out = new ArrayList<>();
        for (Map.Entry<Long, FusedContext> item : sorted) {
            FusedContext context = item.getValue();
            out.add(new ScoredChunk(context.contextId, context.chunk, context.score / totalWeight,
                    context.source, context.sourceSupportCount, false));
            if (out.size() == topK) break;
        }
        return out;
    }

    private double queryWeight(QueryType type) {
        return switch (type) {
            case PRIMARY -> 1.0;
            case VARIANT -> cfg.getRag().getRewrite().getVariantWeight() > 0
                    ? cfg.getRag().getRewrite().getVariantWeight() : 0.8;
            case BROAD -> cfg.getRag().getRewrite().getBroadWeight() > 0
                    ? cfg.getRag().getRewrite().getBroadWeight() : 0.6;
        };
    }

    private List<ScoredChunk> toScored(List<HybridStore.SearchResult> hits) {
        List<ScoredChunk> out = new ArrayList<>();
        if (hits == null) return out;
        for (HybridStore.SearchResult r : hits) {
            out.add(new ScoredChunk(r.contextId, r.chunk, r.score, r.source, r.sourceSupportCount, false));
        }
        return out;
    }

    private record QueryHits(QuerySpec spec, List<HybridStore.SearchResult> hits) {}

    private static class FusedContext {
        private final long contextId;
        private final Chunk chunk;
        private final String source;
        private final int sourceSupportCount;
        private double score;

        private FusedContext(long contextId, Chunk chunk, String source, int sourceSupportCount) {
            this.contextId = contextId;
            this.chunk = chunk;
            this.source = source;
            this.sourceSupportCount = sourceSupportCount;
        }
    }

    public List<Chunk> getChunks() {
        List<InfrastructureService.ChunkRow> rows = infra.loadAllRAGChunks();
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            chunks.add(new Chunk(i, rows.get(i).content));
        }
        return chunks;
    }

    /** Stable parent IDs intended for building RAG evaluation golden datasets. */
    public List<ContextCatalogItem> getEvaluationContexts() {
        List<ContextCatalogItem> out = new ArrayList<>();
        for (InfrastructureService.RagParentContextRow row : infra.loadAllRAGParentContexts()) {
            out.add(new ContextCatalogItem(row.contextId, row.docHash, row.parentIdx, row.content));
        }
        return out;
    }

    public void restoreChunks(List<Chunk> chunks) {
        loaded = !chunks.isEmpty();
        store.restoreChunks(chunks);
    }

    // ============ TF 兜底搜索 ============

    private List<ScoredChunk> tfSearch(String query, int topK) {
        List<InfrastructureService.ChunkRow> rows = infra.loadAllRAGChunks();
        if (rows.isEmpty()) return Collections.emptyList();
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) chunks.add(new Chunk((int) rows.get(i).id, rows.get(i).content));

        Set<String> allTokens = new LinkedHashSet<>();
        List<String> queryTokens = LongTermMemory.tokenize(query);
        allTokens.addAll(queryTokens);
        for (Chunk c : chunks) allTokens.addAll(LongTermMemory.tokenize(c.getContent()));
        List<String> vocabList = new ArrayList<>(allTokens);
        Map<String, Integer> vocabIdx = new HashMap<>();
        for (int i = 0; i < vocabList.size(); i++) vocabIdx.put(vocabList.get(i), i);

        double[] qVec = new double[vocabList.size()];
        for (String t : queryTokens) {
            Integer idx = vocabIdx.get(t);
            if (idx != null) qVec[idx]++;
        }

        List<ScoredChunk> scored = new ArrayList<>();
        for (Chunk c : chunks) {
            double[] cVec = new double[vocabList.size()];
            for (String t : LongTermMemory.tokenize(c.getContent())) {
                Integer idx = vocabIdx.get(t);
                if (idx != null) cVec[idx]++;
            }
            double sim = cosine(qVec, cVec);
            if (sim > 0) scored.add(new ScoredChunk(c.getId(), c, sim, "tf-fallback", 1, true));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        int fetchK = Math.min(scored.size(), Math.max(topK * 4, 10));
        List<Long> childIds = new ArrayList<>();
        for (int i = 0; i < fetchK; i++) childIds.add(scored.get(i).contextId);
        Map<Long, InfrastructureService.RagContextRow> contextsByChild = new HashMap<>();
        for (InfrastructureService.RagContextRow row : infra.loadRAGContextsByChildIDs(childIds)) {
            contextsByChild.put(row.childId, row);
        }
        Map<Long, ScoredChunk> parents = new LinkedHashMap<>();
        for (int i = 0; i < fetchK; i++) {
            ScoredChunk child = scored.get(i);
            InfrastructureService.RagContextRow row = contextsByChild.get(child.contextId);
            if (row == null || row.content == null) {
                parents.putIfAbsent(child.contextId, child);
                continue;
            }
            ScoredChunk current = parents.get(row.contextId);
            if (current == null || child.score > current.score) {
                parents.put(row.contextId, new ScoredChunk(row.contextId, new Chunk(0, row.content), child.score,
                        "tf-fallback", 1, true));
            }
        }
        List<ScoredChunk> contexts = new ArrayList<>(parents.values());
        contexts.sort((a, b) -> Double.compare(b.score, a.score));
        return contexts.subList(0, Math.min(topK, contexts.size()));
    }

    private double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ============ Result types ============

    public static class ScoredChunk {
        /** Stable PostgreSQL parent-context ID used by the evaluator. */
        public long contextId;
        public Chunk chunk;
        public double score;
        public String source;
        public int sourceSupportCount;
        public boolean fallback;

        /** Legacy constructor retained for existing callers and tests. */
        public ScoredChunk(Chunk chunk, double score) {
            this(chunk == null ? -1 : chunk.getId(), chunk, score, "unknown", 0, false);
        }

        public ScoredChunk(long contextId, Chunk chunk, double score, String source,
                           int sourceSupportCount, boolean fallback) {
            this.contextId = contextId;
            this.chunk = chunk;
            this.score = score;
            this.source = source;
            this.sourceSupportCount = sourceSupportCount;
            this.fallback = fallback;
        }

        public ScoredChunk withScore(double newScore) {
            return new ScoredChunk(contextId, chunk, newScore, source, sourceSupportCount, fallback);
        }
    }

    public static class QueryResult {
        public String answer;
        public List<ScoredChunk> results;
        public QueryResult(String answer, List<ScoredChunk> results) {
            this.answer = answer; this.results = results;
        }
    }

    public static class QueryTrace {
        public String originalQuestion;
        public String primaryQuery;
        public List<String> expandedQueries = new ArrayList<>();
        /** First-pass hybrid retrieval before any multi-query RRF fusion. */
        public List<ScoredChunk> primaryRetrievalCandidates = new ArrayList<>();
        public List<ScoredChunk> retrievalCandidates = new ArrayList<>();
        public List<ScoredChunk> rerankedContexts = new ArrayList<>();
        public List<ScoredChunk> finalContexts = new ArrayList<>();
        public String answer;
        public String mode;
        public boolean fallbackUsed;
        public String warning;
    }

    public static class ContextCatalogItem {
        public long contextId;
        public String docHash;
        public int parentIdx;
        public String content;

        public ContextCatalogItem(long contextId, String docHash, int parentIdx, String content) {
            this.contextId = contextId;
            this.docHash = docHash;
            this.parentIdx = parentIdx;
            this.content = content;
        }
    }

    private static List<ScoredChunk> copy(List<ScoredChunk> source) {
        List<ScoredChunk> out = new ArrayList<>();
        if (source == null) return out;
        for (ScoredChunk item : source) {
            if (item != null) out.add(item.withScore(item.score));
        }
        return out;
    }
}
