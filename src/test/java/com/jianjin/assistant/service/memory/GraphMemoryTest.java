package com.jianjin.assistant.service.memory;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.model.MemoryItem;
import com.jianjin.assistant.service.graph.KGStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraphMemoryTest {

    @Test
    void graphWritesOnlyDurableIdsAndLinksResolvedSequence() {
        LongTermMemory longTerm = new LongTermMemory();
        KGStore kg = mock(KGStore.class);
        when(kg.available()).thenReturn(true);
        GraphMemory graph = new GraphMemory("u1", longTerm, kg, 0.8);

        GraphMemory.StoreResult first = graph.store("第一条", 0.7, List.of(1.0, 0.0));
        verify(kg, never()).upsertMemoryNode(anyString(), anyInt(), anyString(), anyDouble());
        graph.syncItemPGID(first.itemId(), 100);

        GraphMemory.StoreResult second = graph.store("第二条", 0.8, List.of(0.0, 1.0));
        graph.syncItemPGID(second.itemId(), 200);

        verify(kg).upsertMemoryNode("u1", 100, "第一条", 0.7);
        verify(kg).upsertMemoryNode("u1", 200, "第二条", 0.8);
        verify(kg, never()).upsertMemoryNode(eq("u1"), eq(first.itemId()), anyString(), anyDouble());
        verify(kg).addMemoryEdge("u1", 100, 200, "FOLLOWS", 1.0);
    }

    @Test
    void outOfOrderPersistenceStillBuildsFollowsEdgeWithDurableIds() {
        LongTermMemory longTerm = new LongTermMemory();
        KGStore kg = mock(KGStore.class);
        when(kg.available()).thenReturn(true);
        GraphMemory graph = new GraphMemory("u1", longTerm, kg, 0.8);
        GraphMemory.StoreResult first = graph.store("第一条", 0.7, List.of(1.0, 0.0));
        GraphMemory.StoreResult second = graph.store("第二条", 0.8, List.of(0.0, 1.0));

        graph.syncItemPGID(second.itemId(), 200);
        graph.syncItemPGID(first.itemId(), 100);

        verify(kg).addMemoryEdge("u1", 100, 200, "FOLLOWS", 1.0);
        verify(kg, never()).addMemoryEdge(eq("u1"), eq(first.itemId()), anyInt(), eq("FOLLOWS"), anyDouble());
    }

    @Test
    void graphCentralityProtectionIsAppliedBeforeInMemoryConsolidation() {
        AppConfig.ConsolidationConfig config = new AppConfig.ConsolidationConfig();
        config.setDedupThreshold(0.95);
        LongTermMemory longTerm = new LongTermMemory();
        longTerm.setConsolidationConfig(config);
        longTerm.storeItem(new MemoryItem(1, "第一条", 0.2, List.of(1.0, 0.0)));
        longTerm.storeItem(new MemoryItem(2, "第二条", 0.9, List.of(0.999, 0.001)));
        List<MemoryItem> items = longTerm.getItems();
        int protectedId = items.get(0).getId();

        KGStore kg = mock(KGStore.class);
        when(kg.available()).thenReturn(true);
        when(kg.getHighCentralityMemoryIds(eq("u1"), anyList(), eq(3))).thenReturn(List.of(protectedId));
        GraphMemory graph = new GraphMemory("u1", longTerm, kg, 0.8);

        LongTermMemory.ConsolidationResult result = graph.graphAwareConsolidate();

        assertNotNull(longTerm.getItemById(protectedId));
        assertEquals(1, longTerm.size());
        assertFalse(result.deleteFromDB.contains(protectedId));
        verify(kg).getHighCentralityMemoryIds(eq("u1"), anyList(), eq(3));
    }
}
