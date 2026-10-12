package com.jianjin.assistant.service.rag;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.domain.rag.HistoryMessage;
import com.jianjin.assistant.domain.rag.QuerySpec;
import com.jianjin.assistant.domain.rag.QueryType;
import com.jianjin.assistant.domain.rag.Rewriter;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.infrastructure.persistence.RagTenantRepository;
import com.jianjin.assistant.model.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagServiceAdaptiveRrfTest {

    @Test
    void secondaryRrfUsesContextIdInsteadOfContentAndQueryTypeWeights() {
        AppConfig cfg = new AppConfig();
        HybridStore store = mock(HybridStore.class);
        when(store.search("primary", 3)).thenReturn(List.of(
                result(10, "same text"), result(20, "primary-only")));
        when(store.search("variant", 3)).thenReturn(List.of(
                result(10, "same text"), result(30, "same text")));

        RagService service = new RagService(cfg, store, mock(TextSplitter.class), mock(InfrastructureService.class));
        try {
            List<RagService.ScoredChunk> results = service.searchMulti(List.of("primary", "variant"), 3);

            assertEquals(3, results.size());
            assertEquals("same text", results.get(0).chunk.getContent());
            assertEquals(1.0 / 61, results.get(0).score, 1e-9);
            assertEquals("primary-only", results.get(1).chunk.getContent());
            assertEquals("same text", results.get(2).chunk.getContent());
        } finally {
            service.close();
        }
    }

    @Test
    void insufficientHybridConsensusTriggersExpansion() {
        AppConfig cfg = new AppConfig();
        HybridStore store = mock(HybridStore.class);
        when(store.getMode()).thenReturn("hybrid");
        List<HybridStore.SearchResult> primary = List.of(
                result(1, "a"), result(2, "b"), result(3, "c"));
        when(store.search("primary", 12)).thenReturn(primary);
        when(store.search("variant", 12)).thenReturn(List.of(result(4, "d")));

        AtomicBoolean expanded = new AtomicBoolean(false);
        Rewriter rewriter = new Rewriter() {
            @Override
            public QuerySpec rewritePrimary(String query, List<HistoryMessage> history) {
                return new QuerySpec("primary", QueryType.PRIMARY);
            }

            @Override
            public List<QuerySpec> expand(String primaryQuery, List<HistoryMessage> history) {
                expanded.set(true);
                return List.of(new QuerySpec("variant", QueryType.VARIANT));
            }
        };

        RagService service = new RagService(cfg, store, mock(TextSplitter.class), mock(InfrastructureService.class));
        service.setRewriter(rewriter);
        service.restoreChunks(List.of(new Chunk(1, "loaded")));
        try {
            RagService.QueryResult output = service.queryWithHistory("question", List.of());
            assertTrue(expanded.get());
            assertEquals(3, output.results.size());
        } finally {
            service.close();
        }
    }

    @Test
    void consensusBelowFinalTopKDoesNotSuppressExpansion() {
        AppConfig cfg = new AppConfig();
        HybridStore store = mock(HybridStore.class);
        when(store.getMode()).thenReturn("hybrid");
        when(store.search("primary", 12)).thenReturn(List.of(
                result(1, "a"), result(2, "b"), result(3, "c"),
                new HybridStore.SearchResult(4, new Chunk(0, "low-ranked consensus"),
                        0.01, "hybrid", 3)));
        when(store.search("variant", 12)).thenReturn(List.of(result(5, "d")));

        AtomicBoolean expanded = new AtomicBoolean(false);
        Rewriter rewriter = new Rewriter() {
            @Override
            public QuerySpec rewritePrimary(String query, List<HistoryMessage> history) {
                return new QuerySpec("primary", QueryType.PRIMARY);
            }

            @Override
            public List<QuerySpec> expand(String primaryQuery, List<HistoryMessage> history) {
                expanded.set(true);
                return List.of(new QuerySpec("variant", QueryType.VARIANT));
            }
        };

        RagService service = new RagService(cfg, store, mock(TextSplitter.class), mock(InfrastructureService.class));
        service.setRewriter(rewriter);
        service.restoreChunks(List.of(new Chunk(1, "loaded")));
        try {
            service.queryWithHistory("question", List.of());
            assertTrue(expanded.get());
        } finally {
            service.close();
        }
    }

    @Test
    void tenantDocumentScopeIsPushedIntoTheStoreSearch() {
        AppConfig cfg = new AppConfig();
        HybridStore store = mock(HybridStore.class);
        when(store.getMode()).thenReturn("hybrid");
        when(store.search("question", 12, Set.of("doc-a"))).thenReturn(List.of(result(1, "owned")));
        RagTenantRepository tenants = mock(RagTenantRepository.class);
        when(tenants.documentHashes("workspace:user")).thenReturn(Set.of("doc-a"));
        when(tenants.allowedContexts("workspace:user", List.of(1L))).thenReturn(Set.of(1L));

        RagService service = new RagService(cfg, store, mock(TextSplitter.class), mock(InfrastructureService.class));
        service.setTenantRepository(tenants);
        service.restoreChunks(List.of(new Chunk(1, "loaded")));
        try {
            RagService.QueryResult output = service.queryWithHistory("workspace:user", "question", List.of());
            assertEquals(1, output.results.size());
            verify(store).search("question", 12, Set.of("doc-a"));
        } finally {
            service.close();
        }
    }

    @Test
    void emptyConnectedExternalIndexesFallBackToPostgresTfSearch() {
        AppConfig cfg = new AppConfig();
        HybridStore store = mock(HybridStore.class);
        when(store.getMode()).thenReturn("hybrid");
        when(store.search("alpha", 12)).thenReturn(List.of());
        InfrastructureService infra = mock(InfrastructureService.class);
        InfrastructureService.ChunkRow child = new InfrastructureService.ChunkRow();
        child.id = 7L;
        child.docHash = "doc-a";
        child.content = "alpha beta";
        when(infra.loadAllRAGChunks()).thenReturn(List.of(child));
        InfrastructureService.RagContextRow parent = new InfrastructureService.RagContextRow();
        parent.childId = 7L;
        parent.contextId = 70L;
        parent.content = "parent alpha beta";
        when(infra.loadRAGContextsByChildIDs(List.of(7L))).thenReturn(List.of(parent));

        RagService service = new RagService(cfg, store, mock(TextSplitter.class), infra);
        service.restoreChunks(List.of(new Chunk(1, "loaded")));
        try {
            RagService.QueryTrace trace = service.traceQuery("alpha");
            assertTrue(trace.fallbackUsed);
            assertEquals(70L, trace.finalContexts.get(0).contextId);
        } finally {
            service.close();
        }
    }

    private static HybridStore.SearchResult result(long contextId, String content) {
        return new HybridStore.SearchResult(contextId, new Chunk(0, content), 0.1, "hybrid", 1);
    }
}
