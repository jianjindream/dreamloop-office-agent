package com.jianjin.assistant.service.document;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.config.WorkspaceStorageConfig;
import com.jianjin.assistant.domain.document.Document;
import com.jianjin.assistant.domain.document.WriteRequest;
import com.jianjin.assistant.infrastructure.persistence.JdbcLibraryRepo;
import com.jianjin.assistant.infrastructure.persistence.WorkspaceDatabase;
import com.jianjin.assistant.interfaces.http.security.RequestIdentity;
import com.jianjin.assistant.interfaces.http.security.RequestIdentityHolder;
import com.jianjin.assistant.service.workspace.WorkspaceAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DocumentAccessServiceTest {
    @TempDir Path directory;

    @Test void privateShareWorkspaceDenyAndRevocationApplyImmediately() {
        try (WorkspaceDatabase database = open()) {
            JdbcLibraryRepo library = new JdbcLibraryRepo(database);
            WorkspaceAccessService members = new WorkspaceAccessService(database);
            RequestIdentity alice = new RequestIdentity("alice", "team-a");
            RequestIdentity bob = new RequestIdentity("bob", "team-a");
            try (var ignored = RequestIdentityHolder.bind(alice)) {
                members.current();
                members.invite("bob", "Bob", "bob@example.com", "editor");
            }
            try (var ignored = RequestIdentityHolder.bind(bob)) { members.current(); }

            WriteRequest request = new WriteRequest("项目机密", "note", Document.SOURCE_UPLOAD, "alice",
                    "预算为 100 万", "", new LinkedHashMap<>());
            request.setWorkspaceId("team-a"); request.setOwnerUserId("alice");
            request.setVisibility(Document.VISIBILITY_PRIVATE);
            var written = library.write(request);
            library.putVersionMetadata(written.version.getId(), "doc_hash", "hash-secret");
            library.putVersionMetadata(written.version.getId(), "index_status", "indexed");

            DocumentAccessService access = new DocumentAccessService(database, library);
            assertTrue(access.canRead(alice, written.document));
            assertFalse(access.canRead(bob, written.document));
            assertFalse(access.readableDocumentHashes(bob).contains("hash-secret"));

            access.replacePermissions(alice, written.document.getId(), Document.VISIBILITY_RESTRICTED,
                    List.of(new DocumentAccessService.PermissionEntry("user", "bob", "read", "allow")));
            assertTrue(access.canRead(bob, written.document.getId()));
            assertFalse(access.canWrite(bob, written.document.getId()));
            assertEquals(Set.of("hash-secret"), access.readableDocumentHashes(bob));

            access.replacePermissions(alice, written.document.getId(), Document.VISIBILITY_PRIVATE, List.of());
            assertFalse(access.canRead(bob, written.document.getId()));
            assertTrue(access.readableDocumentHashes(bob).isEmpty(), "revocation must affect the next retrieval scope");

            access.replacePermissions(alice, written.document.getId(), Document.VISIBILITY_WORKSPACE, List.of());
            assertTrue(access.canRead(bob, written.document.getId()));
            access.replacePermissions(alice, written.document.getId(), Document.VISIBILITY_WORKSPACE,
                    List.of(new DocumentAccessService.PermissionEntry("user", "bob", "read", "deny")));
            assertFalse(access.canRead(bob, written.document.getId()), "explicit deny wins over workspace visibility");
        }
    }

    @Test void permissionsCannotTargetUsersOutsideTheWorkspace() {
        try (WorkspaceDatabase database = open()) {
            JdbcLibraryRepo library = new JdbcLibraryRepo(database);
            WorkspaceAccessService members = new WorkspaceAccessService(database);
            RequestIdentity alice = new RequestIdentity("alice", "team-a");
            try (var ignored = RequestIdentityHolder.bind(alice)) { members.current(); }
            WriteRequest request = new WriteRequest("文档", "note", Document.SOURCE_UPLOAD, "alice",
                    "正文", "", new LinkedHashMap<>());
            request.setWorkspaceId("team-a"); request.setOwnerUserId("alice");
            var document = library.write(request).document;
            DocumentAccessService access = new DocumentAccessService(database, library);
            assertThrows(IllegalArgumentException.class, () -> access.replacePermissions(alice, document.getId(),
                    Document.VISIBILITY_RESTRICTED,
                    List.of(new DocumentAccessService.PermissionEntry("user", "mallory", "read", "allow"))));
        }
    }

    private WorkspaceDatabase open() {
        WorkspaceStorageConfig storage = new WorkspaceStorageConfig(); storage.setDirectory(directory.toString());
        return new WorkspaceDatabase(storage, new AppConfig());
    }
}
