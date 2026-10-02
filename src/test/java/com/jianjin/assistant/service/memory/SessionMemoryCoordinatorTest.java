package com.jianjin.assistant.service.memory;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.service.llm.LlmService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionMemoryCoordinatorTest {
    @Test void compactionKeepsRecentTurnsAndPersistsFallbackSummary() {
        AppConfig cfg = new AppConfig();
        cfg.getMemory().setShortTermMaxTurns(2);
        cfg.getMemory().setMinRecentTurns(1);
        cfg.getMemory().setHistoryMaxTokens(10_000);
        InfrastructureService infra = mock(InfrastructureService.class);
        SessionMemoryCoordinator coordinator = new SessionMemoryCoordinator(cfg, mock(LlmService.class), infra);
        ShortTermMemory stm = new ShortTermMemory(); stm.setMaxTurns(100);
        stm.add(1, "user", "第一轮问题", "10:00:00"); stm.add(2, "assistant", "第一轮回答", "10:00:01");
        stm.add(3, "user", "第二轮问题", "10:01:00"); stm.add(4, "assistant", "第二轮回答", "10:01:01");
        SessionMemoryState state = new SessionMemoryState(stm, new SessionSummary());

        coordinator.compactBeforeRequest(MemoryScope.from("u1", "s1"), state, "第三轮问题");

        assertEquals(2, stm.size());
        assertFalse(state.summary().contextNotes.isEmpty());
        verify(infra).saveSessionSummary(eq("u1"), eq("s1"), any(SessionSummary.class));
        verify(infra, never()).deleteChatHistoryThrough(anyString(), anyString(), anyLong());
    }
}
