package com.jianjin.assistant.service.memory;

import com.jianjin.assistant.model.MemoryItem;
import com.jianjin.assistant.service.graph.KGStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class GraphMemory {

    private static final Logger log = LoggerFactory.getLogger(GraphMemory.class);

    private final LongTermMemory ltm;
    private final KGStore kg;
    private final String userId;
    private final double simThreshold;
    private final Executor executor;
    private volatile int prevId = -1;
    private final ConcurrentMap<Integer, PendingGraphWrite> pendingWrites = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Integer> resolvedIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, List<Integer>> waitingChildren = new ConcurrentHashMap<>();

    public GraphMemory(LongTermMemory ltm, KGStore kg, double simThreshold) {
        this("default", ltm, kg, simThreshold, Runnable::run);
    }

    public GraphMemory(String userId, LongTermMemory ltm, KGStore kg, double simThreshold) {
        this(userId, ltm, kg, simThreshold, Runnable::run);
    }

    public GraphMemory(String userId, LongTermMemory ltm, KGStore kg, double simThreshold, Executor executor) {
        this.userId = userId;
        this.ltm = ltm;
        this.kg = kg;
        this.simThreshold = simThreshold > 0 ? simThreshold : 0.7;
        this.executor = executor;
    }

    public LongTermMemory ltm() { return ltm; }

    /** Store 写入 LTM 并在图中建立节点和边。返回是否新增（false=去重跳过）+ 实际 ID。 */
    public StoreResult store(String content, double importance, List<Double> embedding) {
        return storeClassified(content, importance, embedding, "general", null, null);
    }

    /**
     * 带分类的 Store。沿用 {@link #store} 的图节点/边构建逻辑，仅在 LTM 写入时
     * 多带 category/tags/slotHint 三个字段。
     */
    public StoreResult storeClassified(String content, double importance, List<Double> embedding,
                                       String category, List<String> tags, String slotHint) {
        boolean added = ltm.storeClassified(content, importance, embedding, category, tags, slotHint);
        if (!added) {
            return new StoreResult(false, ltm.findMostSimilarId(embedding));
        }
        // 取刚加入条目
        List<MemoryItem> items = ltm.getItems();
        if (items.isEmpty()) return new StoreResult(true, -1);
        MemoryItem newItem = items.get(items.size() - 1);
        int newId = newItem.getId();

        pendingWrites.put(newId, new PendingGraphWrite(prevId, content, importance));
        prevId = newId;
        return new StoreResult(true, newId);
    }

    private void linkSimilarEdges(MemoryItem newItem, int newId) {
        List<MemoryItem> items = ltm.getItems();
        int start = Math.max(0, items.size() - 51);
        for (int i = start; i < items.size(); i++) {
            MemoryItem old = items.get(i);
            if (old.getId() == newId) continue;
            if (pendingWrites.containsKey(old.getId())) continue;
            if (old.getEmbedding() == null || old.getEmbedding().isEmpty()
                    || newItem.getEmbedding() == null || newItem.getEmbedding().isEmpty()) continue;
            if (old.getEmbedding().size() != newItem.getEmbedding().size()) continue;
            double sim = LongTermMemory.cosine(old.getEmbedding(), newItem.getEmbedding());
            if (sim >= simThreshold) {
                kg.addMemoryEdge(userId, old.getId(), newId, "SIMILAR_TO", sim);
            }
        }
    }

    /** Recall：先做向量召回，再用图扩展邻居 */
    public List<MemoryItem> recall(String query, int topK, List<Double> queryEmbedding) {
        List<MemoryItem> seed = ltm.recall(query, topK, queryEmbedding);
        if (kg == null || !kg.available() || seed.isEmpty()) {
            return seed;
        }
        List<Integer> seedIds = new ArrayList<>();
        for (MemoryItem it : seed) seedIds.add(it.getId());
        List<Integer> expandedIds = kg.expandMemoryNeighbors(userId, seedIds, 1);
        if (expandedIds.isEmpty()) return seed;

        Set<Integer> idSet = new HashSet<>(seedIds);
        List<MemoryItem> expanded = new ArrayList<>();
        for (int id : expandedIds) {
            if (idSet.contains(id)) continue;
            for (MemoryItem it : ltm.getItems()) {
                if (it.getId() == id) {
                    it.setScore(0.45);
                    expanded.add(it);
                    idSet.add(id);
                    break;
                }
            }
        }

        List<MemoryItem> all = new ArrayList<>(seed);
        all.addAll(expanded);
        all.sort(Comparator.comparingDouble(MemoryItem::getScore).reversed());
        if (all.size() > topK) all = all.subList(0, topK);
        return all;
    }

    /** GraphAwareConsolidate：在 LTM 合并基础上保护图中心度高的节点 */
    public LongTermMemory.ConsolidationResult graphAwareConsolidate() {
        if (kg == null || !kg.available()) return ltm.consolidate();

        List<Integer> allIds = ltm.getItems().stream().map(MemoryItem::getId).toList();
        List<Integer> protectedIds = kg.getHighCentralityMemoryIds(userId, allIds, 3);
        Set<Integer> protectedSet = new HashSet<>(protectedIds);
        LongTermMemory.ConsolidationResult result = ltm.consolidate(protectedSet);
        if (!protectedIds.isEmpty()) {
            log.info("图中心度保护：{} 条记忆免于合并或过期删除（入度≥3）", protectedIds.size());
        }

        List<Integer> toDelete = new ArrayList<>(result.deleteFromDB);
        executor.execute(() -> {
            for (int id : toDelete) kg.deleteMemoryNode(userId, id);
        });
        return result;
    }

    public void syncPrevId() {
        List<MemoryItem> items = ltm.getItems();
        if (!items.isEmpty()) prevId = items.get(items.size() - 1).getId();
    }

    public void syncLastItemPGID(int pgId) {
        List<MemoryItem> items = ltm.getItems();
        if (!items.isEmpty()) syncItemPGID(items.get(items.size() - 1).getId(), pgId);
    }

    /** Resolves the temporary in-memory id to its durable id before writing any graph node or edge. */
    public void syncItemPGID(int temporaryId, int pgId) {
        MemoryItem item = ltm.syncItemPGID(temporaryId, pgId);
        if (item == null) return;
        PendingGraphWrite pending = pendingWrites.remove(temporaryId);
        resolvedIds.put(temporaryId, pgId);
        if (prevId == temporaryId) prevId = pgId;
        if (kg == null || !kg.available()) return;

        executor.execute(() -> {
            String content = pending == null ? item.getContent() : pending.content();
            double importance = pending == null ? item.getImportance() : pending.importance();
            kg.upsertMemoryNode(userId, pgId, content, importance);
            if (pending != null && pending.previousId() >= 0) {
                Integer previous = resolvedIds.get(pending.previousId());
                if (previous == null && !pendingWrites.containsKey(pending.previousId())) {
                    previous = pending.previousId();
                }
                if (previous != null) {
                    kg.addMemoryEdge(userId, previous, pgId, "FOLLOWS", 1.0);
                } else {
                    waitingChildren.computeIfAbsent(pending.previousId(), ignored -> new CopyOnWriteArrayList<>())
                            .add(temporaryId);
                }
            }
            List<Integer> children = waitingChildren.remove(temporaryId);
            if (children != null) {
                for (int childTemporaryId : children) {
                    Integer child = resolvedIds.get(childTemporaryId);
                    if (child != null) kg.addMemoryEdge(userId, pgId, child, "FOLLOWS", 1.0);
                }
            }
            linkSimilarEdges(item, pgId);
        });
    }

    public boolean needConsolidation() { return ltm.needConsolidation(); }

    public void markRecalled(int itemId) { ltm.markRecalled(itemId); }

    public int size() { return ltm.size(); }

    public record StoreResult(boolean added, int itemId) {}
    private record PendingGraphWrite(int previousId, String content, double importance) {}
}
