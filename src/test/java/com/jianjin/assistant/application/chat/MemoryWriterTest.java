package com.jianjin.assistant.application.chat;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.service.llm.LlmService;
import com.jianjin.assistant.service.memory.LongTermMemory;
import com.jianjin.assistant.service.memory.PreferenceMemory;
import com.jianjin.assistant.model.MemoryItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryWriterTest {

    @Test
    void duplicateMemoryMetadataUpdateIsPersisted() {
        AppConfig config = new AppConfig();
        config.getLlm().setApiKey("test-key");
        LlmService llm = mock(LlmService.class);
        when(llm.chat(anyString(), anyList())).thenReturn(
                "{\"items\":[{\"category\":\"preference\",\"content\":\"用户喜欢简洁回答\",\"tags\":[\"concise\"]}]}" );
        when(llm.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        InfrastructureService infra = mock(InfrastructureService.class);
        LongTermMemory longTerm = new LongTermMemory();
        longTerm.setConsolidationConfig(config.getMemory().getConsolidation());
        MemoryItem existing = new MemoryItem(12, "用户偏好简洁回答", 0.5, List.of(1.0, 0.0));
        longTerm.storeItem(existing);
        PreferenceMemory preferences = new PreferenceMemory();
        MemoryWriter writer = new MemoryWriter(config, llm, preferences, longTerm, null, infra);

        writer.writeNow("u1", "请继续简洁回答", "我会保持简洁。", preferences, longTerm, null);

        verify(infra).updateLongTermItemClassified(eq("u1"), eq(12), eq("用户偏好简洁回答"), eq(0.7),
                anyString(), eq("preference"), eq("[\"concise\"]"), eq("Profile"));
        verify(infra, never()).saveLongTermItemClassified(anyString(), anyString(), anyDouble(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void classificationPromptIncludesUserEvidenceAndPersistsClassifiedMemory() {
        AppConfig config = new AppConfig();
        config.getLlm().setApiKey("test-key");
        LlmService llm = mock(LlmService.class);
        when(llm.chat(anyString(), anyList())).thenReturn(
                "{\"items\":[{\"category\":\"identity\",\"content\":\"用户姓名：小林\",\"tags\":[\"name\"]}]}" );
        when(llm.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        InfrastructureService infra = mock(InfrastructureService.class);
        when(infra.saveLongTermItemClassified(anyString(), anyString(), anyDouble(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(12);
        PreferenceMemory preferences = new PreferenceMemory();
        LongTermMemory longTerm = new LongTermMemory();
        MemoryWriter writer = new MemoryWriter(config, llm, preferences, longTerm, null, infra);

        writer.writeNow("u1", "我叫小林", "你好，小林。", preferences, longTerm, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messages = ArgumentCaptor.forClass(List.class);
        verify(llm).chat(eq(""), messages.capture());
        String prompt = messages.getValue().get(0).get("content");
        assertTrue(prompt.contains("用户消息：我叫小林"));
        assertTrue(prompt.contains("禁止把助手自行推断"));
        assertEquals("用户姓名：小林", preferences.getData().get("姓名"));
        assertEquals("identity", longTerm.getItems().get(0).getCategory());
        verify(infra).saveLongTermItemClassified(eq("u1"), eq("用户姓名：小林"), eq(0.9),
                anyString(), eq("identity"), eq("[\"name\"]"), eq("Profile"));
    }
}
