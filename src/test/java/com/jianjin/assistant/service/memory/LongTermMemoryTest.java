package com.jianjin.assistant.service.memory;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.model.MemoryItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LongTermMemoryTest {

    @Test
    void consolidationKeepsProtectedItemsInMemory() {
        LongTermMemory memory = configuredMemory();
        memory.storeItem(item(1, "用户喜欢简洁回答", 0.4, List.of(1.0, 0.0),
                "preference", List.of("style"), "Profile"));
        memory.storeItem(item(2, "用户偏好简洁回答", 0.9, List.of(0.99, 0.01),
                "preference", List.of("concise"), "Profile"));
        List<MemoryItem> before = memory.getItems();
        int protectedId = before.get(0).getId();
        int removableId = before.get(1).getId();

        LongTermMemory.ConsolidationResult result = memory.consolidate(Set.of(protectedId));

        assertNotNull(memory.getItemById(protectedId));
        assertNull(memory.getItemById(removableId));
        assertEquals(List.of(removableId), result.deleteFromDB);
    }

    @Test
    void mergePreservesClassificationTagsAndSlotHint() {
        LongTermMemory memory = configuredMemory();
        memory.storeItem(item(1, "工具请求超时", 0.8, List.of(1.0, 0.0),
                "tool_failure", List.of("network"), "ToolState"));
        memory.storeItem(item(2, "工具网络超时", 0.7, List.of(0.8, 0.6),
                "general", List.of("timeout"), null));

        LongTermMemory.ConsolidationResult result = memory.consolidate();

        assertEquals(1, result.merged);
        MemoryItem merged = memory.getItems().get(0);
        assertEquals("tool_failure", merged.getCategory());
        assertEquals("ToolState", merged.getSlotHint());
        assertTrue(merged.getTags().containsAll(List.of("network", "timeout")));
    }

    @Test
    void oldImportantProtectedItemDoesNotExpire() {
        LongTermMemory memory = configuredMemory();
        MemoryItem item = new MemoryItem(42, "需要保留", 0.1, List.of(1.0));
        item.setCreatedAt(LocalDateTime.now().minusDays(60));
        item.setLastAccessed(item.getCreatedAt());
        memory.storeItem(item);
        memory.store("占位项", 0.5, List.of(0.0));

        memory.consolidate(Set.of(42));

        assertNotNull(memory.getItemById(42));
    }

    private static LongTermMemory configuredMemory() {
        AppConfig.ConsolidationConfig config = new AppConfig.ConsolidationConfig();
        config.setDedupThreshold(0.98);
        config.setSimilarityThreshold(0.75);
        config.setTriggerInterval(1);
        LongTermMemory memory = new LongTermMemory();
        memory.setConsolidationConfig(config);
        return memory;
    }

    private static MemoryItem item(int id, String content, double importance, List<Double> embedding,
                                   String category, List<String> tags, String slotHint) {
        MemoryItem item = new MemoryItem(id, content, importance, embedding);
        item.setCategory(category);
        item.setTags(tags);
        item.setSlotHint(slotHint);
        return item;
    }
}
