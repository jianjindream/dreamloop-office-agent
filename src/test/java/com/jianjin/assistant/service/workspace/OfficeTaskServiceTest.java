package com.jianjin.assistant.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.config.WorkspaceStorageConfig;
import com.jianjin.assistant.infrastructure.persistence.WorkspaceDatabase;
import com.jianjin.assistant.interfaces.http.security.RequestIdentity;
import com.jianjin.assistant.interfaces.http.security.RequestIdentityHolder;
import com.jianjin.assistant.service.office.WeeklyReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfficeTaskServiceTest {
    @TempDir Path directory;

    @Test void persistsAndCompletesWeeklyReportTask() throws Exception {
        try (WorkspaceDatabase database = open(); var ignored = RequestIdentityHolder.bind(new RequestIdentity("alice", "workspace"))) {
            ObjectMapper json = new ObjectMapper().findAndRegisterModules();
            WorkspaceAccessService access = new WorkspaceAccessService(database); access.current();
            PilotService pilot = new PilotService(database, json, access);
            WeeklyReportService reports = mock(WeeklyReportService.class);
            OfficeConnectionService connections = mock(OfficeConnectionService.class);
            WeeklyReportService.Result generated = new WeeklyReportService.Result("doc", "ver", 1, "# 周报",
                    new WeeklyReportService.Review(List.of(), List.of(), List.of()), List.of(), "not_indexed");
            when(reports.generate(eq("workspace:alice"), any())).thenReturn(generated);
            when(reports.documentType(any())).thenReturn("note");
            OfficeTaskService service = new OfficeTaskService(database, json, reports, connections, access, pilot);
            try {
                var task = service.submitWeeklyReport(new WeeklyReportService.GenerateRequest("项目", LocalDate.of(2026,10,19),
                        LocalDate.of(2026,10,23), "builtin-weekly-report", List.of("source"), "", true));
                OfficeTaskService.Task current = task;
                for (int i=0; i<100 && !List.of("succeeded","failed").contains(current.status()); i++) {
                    Thread.sleep(20); current = service.get(task.id());
                }
                assertEquals("succeeded", current.status()); assertEquals(100, current.progress());
                assertEquals(1, service.list().size());
            } finally { service.close(); }
        }
    }

    private WorkspaceDatabase open() {
        WorkspaceStorageConfig storage = new WorkspaceStorageConfig(); storage.setDirectory(directory.toString());
        return new WorkspaceDatabase(storage, new AppConfig());
    }
}
