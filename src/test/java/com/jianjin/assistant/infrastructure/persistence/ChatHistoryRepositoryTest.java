package com.jianjin.assistant.infrastructure.persistence;

import com.jianjin.assistant.infrastructure.platform.PostgresConnector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ChatHistoryRepositoryTest {
    @Test void memoryFallbackProvidesScopedSessionsAndFullMessageHistory() {
        ChatHistoryRepository repository = new ChatHistoryRepository(mock(PostgresConnector.class));
        repository.save("ws-a:user", "s1", "user", "产品发布计划");
        repository.save("ws-a:user", "s1", "assistant", "这是计划");
        repository.save("ws-b:user", "s1", "user", "另一个空间");

        assertEquals(1, repository.listSessions("ws-a:user").size());
        assertEquals("产品发布计划", repository.listSessions("ws-a:user").get(0).title);
        assertEquals(2, repository.load("ws-a:user", "s1", 0, 100).size());
        assertEquals(1, repository.load("ws-b:user", "s1", 0, 100).size());
        assertTrue(repository.renameSession("ws-a:user", "s1", "发布方案"));
        assertEquals("发布方案", repository.listSessions("ws-a:user").get(0).title);
        assertTrue(repository.deleteSession("ws-a:user", "s1"));
        assertTrue(repository.listSessions("ws-a:user").isEmpty());
    }
}
