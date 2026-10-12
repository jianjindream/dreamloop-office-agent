package com.jianjin.assistant.service.rag;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.service.graph.GraphSearchResult;
import com.jianjin.assistant.service.graph.KGStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridStoreRrfTest {

    @Test
    void hybridSearchUsesConfiguredRankWeightsAndTracksSourceConsensus() {
        AppConfig cfg = new AppConfig();
        cfg.getRag().setSemanticWeight(0.7);
        cfg.getRag().setKeywordWeight(1.0);
        cfg.getNeo4j().setWeight(0.3);

        InfrastructureService infra = mock(InfrastructureService.class);
        when(infra.getMilvusStatus()).thenReturn("connected");
        when(infra.getEsStatus()).thenReturn("connected");
        when(infra.milvusSearchWithScores(anyList(), anyInt())).thenReturn(List.of(
                new InfrastructureService.MilvusHit(10L, 0.1f),
                new InfrastructureService.MilvusHit(11L, 0.2f)));
        when(infra.searchRAGChunks(anyString(), anyInt())).thenReturn(List.of(
                new InfrastructureService.ESHit(11L, 99.0),
                new InfrastructureService.ESHit(10L, 98.0)));
        when(infra.loadRAGContextsByChildIDs(anyList())).thenAnswer(invocation -> {
            List<InfrastructureService.RagContextRow> rows = new ArrayList<>();
            List<Long> ids = invocation.getArgument(0);
            for (long id : ids) {
                InfrastructureService.RagContextRow row = new InfrastructureService.RagContextRow();
                row.childId = id;
                row.contextId = id + 100;
                row.content = "parent-" + id;
                rows.add(row);
            }
            return rows;
        });

        KGStore kg = mock(KGStore.class);
        when(kg.available()).thenReturn(true);
        // A deliberately huge raw graph score must not dominate cross-source RRF.
        when(kg.search(anyString(), anyInt())).thenReturn(List.of(
                new GraphSearchResult(12L, 999.0, List.of("entity"), List.of())));

        HybridStore store = new HybridStore(cfg, infra);
        store.setEmbedFn(query -> List.of(0.1));
        store.setKGStore(kg);

        List<HybridStore.SearchResult> results = store.search("query", 3);

        assertEquals(3, results.size());
        assertEquals(111L, results.get(0).contextId);
        assertEquals(2, results.get(0).sourceSupportCount);
        assertEquals(1, results.get(2).sourceSupportCount);
        assertEquals(0.3 / 61 / 2.0, results.get(2).score, 1e-9);
        store.close();
    }

    @Test
    void hybridSwitchDisablesEsFusionWhenSemanticSearchIsAvailable() {
        AppConfig cfg = new AppConfig();
        cfg.getRag().setEnableHybridSearch(false);
        InfrastructureService infra = mock(InfrastructureService.class);
        when(infra.getMilvusStatus()).thenReturn("connected");
        when(infra.getEsStatus()).thenReturn("connected");
        when(infra.milvusSearchWithScores(anyList(), anyInt())).thenReturn(List.of(
                new InfrastructureService.MilvusHit(10L, 0.9f)));
        when(infra.loadRAGContextsByChildIDs(anyList())).thenReturn(List.of(context(10L)));

        HybridStore store = new HybridStore(cfg, infra);
        store.setEmbedFn(query -> List.of(0.1));
        try {
            assertEquals("semantic", store.getMode());
            assertEquals(1, store.search("query", 3).size());
            verify(infra, never()).searchRAGChunks(anyString(), anyInt());
        } finally {
            store.close();
        }
    }

    @Test
    void zeroSemanticWeightDisablesMilvusInsteadOfRestoringFallbackWeight() {
        AppConfig cfg = new AppConfig();
        cfg.getRag().setSemanticWeight(0);
        InfrastructureService infra = mock(InfrastructureService.class);
        when(infra.getMilvusStatus()).thenReturn("connected");
        when(infra.getEsStatus()).thenReturn("connected");
        when(infra.searchRAGChunks(anyString(), anyInt())).thenReturn(List.of(
                new InfrastructureService.ESHit(10L, 1.0)));
        when(infra.loadRAGContextsByChildIDs(anyList())).thenReturn(List.of(context(10L)));

        HybridStore store = new HybridStore(cfg, infra);
        store.setEmbedFn(query -> List.of(0.1));
        try {
            assertEquals("keyword", store.getMode());
            assertEquals(1, store.search("query", 3).size());
            verify(infra, never()).milvusSearchWithScores(anyList(), anyInt());
        } finally {
            store.close();
        }
    }

    @Test
    void twelveParentCandidatesFetchFortyEightChildrenPerSource() {
        AppConfig cfg = new AppConfig();
        InfrastructureService infra = mock(InfrastructureService.class);
        when(infra.getMilvusStatus()).thenReturn("connected");
        when(infra.getEsStatus()).thenReturn("connected");

        HybridStore store = new HybridStore(cfg, infra);
        store.setEmbedFn(query -> List.of(0.1));
        try {
            store.search("query", 12);
            verify(infra).milvusSearchWithScores(anyList(), org.mockito.ArgumentMatchers.eq(48));
            verify(infra).searchRAGChunks("query", 48);
        } finally {
            store.close();
        }
    }

    private static InfrastructureService.RagContextRow context(long childId) {
        InfrastructureService.RagContextRow row = new InfrastructureService.RagContextRow();
        row.childId = childId;
        row.contextId = childId + 100;
        row.content = "parent-" + childId;
        return row;
    }
}
