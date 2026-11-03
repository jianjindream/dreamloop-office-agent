package com.jianjin.assistant.service.workspace;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.config.WorkspaceStorageConfig;
import com.jianjin.assistant.infrastructure.persistence.WorkspaceDatabase;
import com.jianjin.assistant.interfaces.http.error.ApiException;
import com.jianjin.assistant.interfaces.http.security.RequestIdentity;
import com.jianjin.assistant.interfaces.http.security.RequestIdentityHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceAccessServiceTest {
    @TempDir Path directory;

    @Test void bootstrapsOwnerAndEnforcesInvitedViewerPermissions() {
        try (WorkspaceDatabase database = open()) {
            WorkspaceAccessService service = new WorkspaceAccessService(database);
            try (var ignored = RequestIdentityHolder.bind(new RequestIdentity("alice", "workspace"))) {
                assertEquals("owner", service.current().role());
                assertEquals("viewer", service.invite("bob", "Bob", "bob@example.com", "viewer").role());
                assertEquals(2, service.list().size());
            }
            try (var ignored = RequestIdentityHolder.bind(new RequestIdentity("bob", "workspace"))) {
                assertEquals("active", service.current().status());
                assertThrows(ApiException.class, service::requireEditor);
            }
            try (var ignored = RequestIdentityHolder.bind(new RequestIdentity("mallory", "workspace"))) {
                assertThrows(ApiException.class, service::current);
            }
        }
    }

    @Test void protectsLastOwner() {
        try (WorkspaceDatabase database = open(); var ignored = RequestIdentityHolder.bind(new RequestIdentity("alice", "workspace"))) {
            WorkspaceAccessService service = new WorkspaceAccessService(database);
            service.current();
            assertThrows(IllegalArgumentException.class, () -> service.update("alice", "viewer", "active"));
            assertThrows(IllegalArgumentException.class, () -> service.remove("alice"));
        }
    }

    private WorkspaceDatabase open() {
        WorkspaceStorageConfig storage = new WorkspaceStorageConfig(); storage.setDirectory(directory.toString());
        return new WorkspaceDatabase(storage, new AppConfig());
    }
}
