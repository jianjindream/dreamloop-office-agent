package com.jianjin.assistant.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.config.WorkspaceStorageConfig;
import com.jianjin.assistant.infrastructure.persistence.WorkspaceDatabase;
import com.jianjin.assistant.interfaces.http.security.RequestIdentity;
import com.jianjin.assistant.interfaces.http.security.RequestIdentityHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PilotServiceTest {
    @TempDir Path directory;

    @Test void aggregatesCompletionQualityReuseAndFeedbackMetrics() {
        try (WorkspaceDatabase database = open(); var ignored = RequestIdentityHolder.bind(new RequestIdentity("alice", "workspace"))) {
            WorkspaceAccessService access = new WorkspaceAccessService(database); access.current();
            PilotService service = new PilotService(database, new ObjectMapper(), access);
            service.record(new PilotService.EventRequest("report_generated", "r1", 1000L, null, null, true, null, "", Map.of()));
            service.record(new PilotService.EventRequest("report_completed", "r1", 120_000L, 8, 1, null, null, "", Map.of()));
            service.record(new PilotService.EventRequest("report_exported", "r1", null, null, null, null, null, "", Map.of()));
            service.record(new PilotService.EventRequest("feedback_submitted", "r1", null, null, null, null, 4, "引用很清楚", Map.of()));
            PilotService.Dashboard dashboard = service.dashboard();
            assertEquals(1, dashboard.generatedReports()); assertEquals(1, dashboard.completedReports());
            assertEquals(120.0, dashboard.averageCompletionSeconds()); assertEquals(8.0, dashboard.averageEditCount());
            assertEquals(1.0, dashboard.factErrorRate()); assertEquals(1.0, dashboard.reuseRate());
            assertEquals(4.0, dashboard.averageRating());
        }
    }

    private WorkspaceDatabase open() {
        WorkspaceStorageConfig storage = new WorkspaceStorageConfig(); storage.setDirectory(directory.toString());
        return new WorkspaceDatabase(storage, new AppConfig());
    }
}
