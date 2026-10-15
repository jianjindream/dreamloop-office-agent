package com.jianjin.assistant.infrastructure.persistence;

import com.jianjin.assistant.infrastructure.platform.PostgresConnector;
import com.jianjin.assistant.service.memory.SessionSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MemoryRepositoryFallbackTest {

    @Test
    void preferencesRemainAvailableWhenPostgresIsOffline() {
        PreferenceRepository repository = new PreferenceRepository(mock(PostgresConnector.class));

        repository.save("u1", "语言", "中文");

        assertEquals("中文", repository.loadAll("u1").get("语言"));
        assertTrue(repository.loadAll("u2").isEmpty());
    }

    @Test
    void longTermItemsSupportOfflineCrud() {
        LongTermRepository repository = new LongTermRepository(mock(PostgresConnector.class));
        int id = repository.saveClassified("u1", "偏好简洁", 0.7, "[1.0,0.0]",
                "preference", "[\"style\"]", "Profile");

        assertTrue(id > 0);
        LongTermRepository.Row stored = repository.loadAll("u1").get(0);
        assertEquals("preference", stored.category);
        assertEquals(List.of("style"), stored.tags);
        assertEquals(0.7, stored.baseImportance);
        assertEquals(0, stored.recallCount);

        Timestamp recalledAt = new Timestamp(System.currentTimeMillis());
        repository.recordRecall("u1", id, 0.75, 1, recalledAt);
        LongTermRepository.Row recalled = repository.loadAll("u1").get(0);
        assertEquals(1, recalled.recallCount);
        assertNotNull(recalled.lastRecalledAt);
        assertEquals(0.75, recalled.importance);

        repository.updateClassified("u1", id, "偏好非常简洁", 0.9, "[0.9,0.1]",
                "preference", "[\"style\",\"concise\"]", "Profile");
        Timestamp lastRecalledAt = recalled.lastRecalledAt;
        repository.updateMetrics("u1", id, 0.8, 0.9, 3, lastRecalledAt);
        LongTermRepository.Row updated = repository.loadAll("u1").get(0);
        assertEquals("偏好非常简洁", updated.content);
        assertEquals(0.8, updated.importance);
        assertEquals(0.9, updated.baseImportance);
        assertEquals(3, updated.recallCount);
        assertEquals(lastRecalledAt, updated.lastRecalledAt);
        assertEquals(List.of("style", "concise"), updated.tags);

        repository.deleteAll("u1", List.of(id));
        assertTrue(repository.loadAll("u1").isEmpty());
    }

    @Test
    void sessionSummaryFallbackUsesDefensiveCopiesAndDeletesByScope() {
        SessionSummaryRepository repository = new SessionSummaryRepository(mock(PostgresConnector.class));
        SessionSummary summary = new SessionSummary();
        summary.goal = "完成报告";
        summary.pending.add("补充证据");
        summary.summarizedThroughId = 7;

        repository.save("u1", "s1", summary);
        summary.pending.clear();

        SessionSummary loaded = repository.load("u1", "s1");
        assertEquals(List.of("补充证据"), loaded.pending);
        assertEquals(7, loaded.summarizedThroughId);
        assertTrue(repository.load("u1", "s2").isEmpty());

        repository.delete("u1", "s1");
        assertTrue(repository.load("u1", "s1").isEmpty());
    }
}
