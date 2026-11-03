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

import static org.junit.jupiter.api.Assertions.*;

class OfficeConnectionServiceTest {
    @TempDir Path directory;

    @Test void storesOnlyCredentialReferencesAndTracksConnectionLifecycle() {
        try (WorkspaceDatabase database = open(); var ignored = RequestIdentityHolder.bind(new RequestIdentity("alice", "workspace"))) {
            WorkspaceAccessService access = new WorkspaceAccessService(database); access.current();
            OfficeConnectionService service = new OfficeConnectionService(database, new ObjectMapper(), access);
            assertThrows(IllegalArgumentException.class, () -> service.create("microsoft365", "M365", "raw-token", Map.of()));
            assertThrows(IllegalArgumentException.class, () -> service.create("microsoft365", "M365", "env:TOKEN", Map.of("api_token", "leak")));

            var manual = service.create("manual_upload", "手动资料", "", Map.of("root_folder", "项目"));
            assertEquals("connected", manual.status());
            assertEquals("connected", service.test(manual.id()).status());
            assertEquals(0, service.syncSnapshot("workspace", manual.id()).get("imported_count"));

            var external = service.create("microsoft365", "M365", "env:TEST_MISSING_OFFICE_TOKEN", Map.of("site", "team"));
            assertEquals("authorization_required", service.test(external.id()).status());
            assertEquals(2, service.list().size());
        }
    }

    private WorkspaceDatabase open() {
        WorkspaceStorageConfig storage = new WorkspaceStorageConfig(); storage.setDirectory(directory.toString());
        return new WorkspaceDatabase(storage, new AppConfig());
    }
}
