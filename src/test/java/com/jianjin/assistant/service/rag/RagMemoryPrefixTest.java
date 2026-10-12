package com.jianjin.assistant.service.rag;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.model.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RagMemoryPrefixTest {

    @Test
    void userMemoryPrefixReachesRagGenerationPrompt() {
        AppConfig config = new AppConfig();
        HybridStore store = mock(HybridStore.class);
        when(store.getMode()).thenReturn("hybrid");
        when(store.search("问题", 12)).thenReturn(List.of(
                new HybridStore.SearchResult(1, new Chunk(1, "知识上下文"), 0.9, "hybrid", 1)));
        RagService service = new RagService(config, store, mock(TextSplitter.class), mock(InfrastructureService.class));
        AtomicReference<String> systemPrompt = new AtomicReference<>();
        service.setGenerateFn((system, user) -> {
            systemPrompt.set(system);
            return "回答";
        });
        service.restoreChunks(List.of(new Chunk(1, "知识上下文")));

        try {
            service.queryWithHistory("owner", "问题", List.of(), "【用户画像】\n- 语言: 中文");
            assertTrue(systemPrompt.get().contains("【用户画像】"));
            assertTrue(systemPrompt.get().contains("请仅根据提供的上下文内容回答问题"));
        } finally {
            service.close();
        }
    }
}
