package com.jianjin.assistant.infrastructure.persistence;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.config.WorkspaceStorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChatHistoryRepositoryTest {
    @TempDir Path directory;
    @Test void durableRepositoryProvidesScopedSessionsAndFullMessageHistory() {
        WorkspaceStorageConfig config = new WorkspaceStorageConfig();
        config.setDirectory(directory.toString());
        try (WorkspaceDatabase database = new WorkspaceDatabase(config, new AppConfig())) {
        ChatHistoryRepository repository = new ChatHistoryRepository(database);
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
        assertEquals(1, repository.listSessions("ws-b:user").size());
        }
    }
}
