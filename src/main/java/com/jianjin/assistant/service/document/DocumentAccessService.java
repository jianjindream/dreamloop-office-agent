package com.jianjin.assistant.service.document;

import com.jianjin.assistant.domain.document.Document;
import com.jianjin.assistant.domain.document.DocumentVersion;
import com.jianjin.assistant.domain.document.LibraryRepo;
import com.jianjin.assistant.infrastructure.persistence.WorkspaceDatabase;
import com.jianjin.assistant.interfaces.http.security.RequestIdentity;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Central object-level authorization for documents and their RAG indexes. */
@Service
public class DocumentAccessService {
    public static final String READ = "read";
    public static final String WRITE = "write";
    public static final String MANAGE = "manage";

    public record PermissionEntry(String principalType, String principalId, String permission, String effect) { }
    public record PermissionView(String documentId, String visibility, long permissionVersion,
                                 List<PermissionEntry> entries) { }

    private final WorkspaceDatabase database;
    private final LibraryRepo library;

    public DocumentAccessService(WorkspaceDatabase database, LibraryRepo library) {
        this.database = database;
        this.library = library;
    }

    public List<Document> listReadable(RequestIdentity identity, boolean deleted) {
        List<Document> source = deleted ? library.listDeleted() : library.list();
        List<Document> result = new ArrayList<>();
        for (Document document : source) {
            boolean allowed = deleted ? canManage(identity, document) : canRead(identity, document);
            if (allowed) result.add(decorate(identity, document));
        }
        return result;
    }

    public Document decorate(RequestIdentity identity, Document document) {
        Security security = security(document);
        document.setWorkspaceId(security.workspaceId);
        document.setOwnerUserId(security.ownerUserId);
        document.setVisibility(security.visibility);
        document.setPermissionVersion(security.permissionVersion);
        document.setCanManage(canManage(identity, document));
        document.setAccessLevel(accessLevel(identity, document));
        return document;
    }

    public boolean canRead(RequestIdentity identity, String documentId) {
        try { return canRead(identity, library.get(documentId).document); }
        catch (RuntimeException ignored) { return false; }
    }

    public boolean canWrite(RequestIdentity identity, String documentId) {
        try { return canWrite(identity, library.get(documentId).document); }
        catch (RuntimeException ignored) { return false; }
    }

    public boolean canManage(RequestIdentity identity, String documentId) {
        try { return canManage(identity, library.get(documentId).document); }
        catch (RuntimeException ignored) { return false; }
    }

    public boolean canRead(RequestIdentity identity, Document document) {
        AccessContext context = context(identity, document);
        if (!context.active || !context.sameWorkspace) return false;
        if (context.documentOwner || context.workspaceAdministrator) return true;
        AclDecision acl = aclDecision(document.getId(), identity.userId(), context.role, READ);
        if (acl.denied) return false;
        if (Document.VISIBILITY_WORKSPACE.equals(context.security.visibility)) return true;
        return Document.VISIBILITY_RESTRICTED.equals(context.security.visibility) && acl.allowed;
    }

    public boolean canWrite(RequestIdentity identity, Document document) {
        AccessContext context = context(identity, document);
        if (!context.active || !context.sameWorkspace) return false;
        if (context.documentOwner || context.workspaceAdministrator) return true;
        if (!List.of("owner", "admin", "editor").contains(context.role)) return false;
        AclDecision acl = aclDecision(document.getId(), identity.userId(), context.role, WRITE);
        return !acl.denied && acl.allowed;
    }

    public boolean canManage(RequestIdentity identity, Document document) {
        AccessContext context = context(identity, document);
        if (!context.active || !context.sameWorkspace) return false;
        if (context.documentOwner || context.workspaceAdministrator) return true;
        AclDecision acl = aclDecision(document.getId(), identity.userId(), context.role, MANAGE);
        return !acl.denied && acl.allowed;
    }

    public String accessLevel(RequestIdentity identity, Document document) {
        if (canManage(identity, document)) return MANAGE;
        if (canWrite(identity, document)) return WRITE;
        if (canRead(identity, document)) return READ;
        return "none";
    }

    /** Returns every indexed version the caller may use. This is recalculated per request so revocation is immediate. */
    public Set<String> readableDocumentHashes(RequestIdentity identity) {
        Set<String> hashes = new LinkedHashSet<>();
        for (Document document : library.list()) {
            if (!canRead(identity, document)) continue;
            for (DocumentVersion version : library.listVersions(document.getId())) {
                Object hash = version.getMetadata().get("doc_hash");
                Object status = version.getMetadata().get("index_status");
                if (hash != null && "indexed".equals(String.valueOf(status))) hashes.add(String.valueOf(hash));
            }
        }
        return hashes;
    }

    public Set<String> readableDocumentHashes(String ownerKey) {
        return readableDocumentHashes(identity(ownerKey));
    }

    public boolean canReadHash(RequestIdentity identity, String docHash) {
        return readableDocumentHashes(identity).contains(docHash);
    }

    public PermissionView permissions(RequestIdentity identity, String documentId) {
        if (!canManage(identity, documentId)) throw new IllegalArgumentException("文档不存在或无权管理");
        Document document = library.get(documentId).document;
        Security security = security(document);
        return new PermissionView(documentId, security.visibility, security.permissionVersion, entries(documentId));
    }

    public PermissionView replacePermissions(RequestIdentity identity, String documentId, String visibility,
                                             Collection<PermissionEntry> requested) {
        if (!canManage(identity, documentId)) throw new IllegalArgumentException("文档不存在或无权管理");
        String normalizedVisibility = normalizeVisibility(visibility);
        List<PermissionEntry> normalized = normalizeEntries(identity.workspaceId(), requested);
        if (normalized.size() > 200) throw new IllegalArgumentException("单个文档最多配置 200 条权限");
        database.transaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM document_permissions WHERE document_id=?")) {
                delete.setString(1, documentId); delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO document_permissions(document_id,workspace_id,principal_type,principal_id,permission,effect,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
                for (PermissionEntry entry : normalized) {
                    insert.setString(1, documentId); insert.setString(2, identity.workspaceId());
                    insert.setString(3, entry.principalType); insert.setString(4, entry.principalId);
                    insert.setString(5, entry.permission); insert.setString(6, entry.effect);
                    insert.setString(7, identity.userId()); insert.setObject(8, timestamp(Instant.now())); insert.addBatch();
                }
                insert.executeBatch();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE office_documents SET visibility=?,permission_version=permission_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND workspace_id=?")) {
                update.setString(1, normalizedVisibility); update.setString(2, documentId); update.setString(3, identity.workspaceId());
                if (update.executeUpdate() != 1) throw new IllegalArgumentException("文档不存在");
            }
            return null;
        });
        Document updated = library.get(documentId).document;
        Security security = security(updated);
        return new PermissionView(documentId, security.visibility, security.permissionVersion, entries(documentId));
    }

    private List<PermissionEntry> normalizeEntries(String workspaceId, Collection<PermissionEntry> entries) {
        List<PermissionEntry> result = new ArrayList<>();
        if (entries == null) return result;
        Set<String> unique = new LinkedHashSet<>();
        for (PermissionEntry entry : entries) {
            if (entry == null) continue;
            String type = lower(entry.principalType);
            String id = entry.principalId == null ? "" : entry.principalId.trim();
            String permission = lower(entry.permission);
            String effect = lower(entry.effect);
            if (!List.of("user", "role").contains(type)) throw new IllegalArgumentException("principal_type 必须为 user 或 role");
            if (!id.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("principal_id 格式不正确");
            if (!List.of(READ, WRITE, MANAGE).contains(permission)) throw new IllegalArgumentException("permission 必须为 read、write 或 manage");
            if (!List.of("allow", "deny").contains(effect)) throw new IllegalArgumentException("effect 必须为 allow 或 deny");
            if ("role".equals(type) && !List.of("owner", "admin", "editor", "viewer").contains(id)) {
                throw new IllegalArgumentException("不支持的工作区角色");
            }
            if ("user".equals(type) && !workspaceMemberExists(workspaceId, id)) {
                throw new IllegalArgumentException("用户不属于当前工作区: " + id);
            }
            String key = type + "\n" + id + "\n" + permission;
            if (!unique.add(key)) throw new IllegalArgumentException("存在重复权限主体");
            result.add(new PermissionEntry(type, id, permission, effect));
        }
        return result;
    }

    private List<PermissionEntry> entries(String documentId) {
        return database.transaction(connection -> {
            List<PermissionEntry> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT principal_type,principal_id,permission,effect FROM document_permissions WHERE document_id=? ORDER BY principal_type,principal_id,permission")) {
                statement.setString(1, documentId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) result.add(new PermissionEntry(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                }
            }
            return result;
        });
    }

    private AccessContext context(RequestIdentity identity, Document document) {
        Security security = security(document);
        MemberState member = member(identity.workspaceId(), identity.userId());
        boolean sameWorkspace = identity.workspaceId().equals(security.workspaceId);
        boolean administrator = "owner".equals(member.role) || "admin".equals(member.role);
        return new AccessContext(security, sameWorkspace, member.active, member.role,
                identity.userId().equals(security.ownerUserId), administrator);
    }

    /** Lazily migrates pre-ACL rows that only carry owner_key in version metadata. */
    private Security security(Document document) {
        String workspace = document.getWorkspaceId();
        String owner = document.getOwnerUserId();
        String visibility = normalizeVisibility(document.getVisibility());
        long version = Math.max(1, document.getPermissionVersion());
        if (workspace != null && !workspace.isBlank() && owner != null && !owner.isBlank()) {
            return new Security(workspace, owner, visibility, version);
        }
        String legacy = "default:default";
        try {
            Object stored = library.get(document.getId()).version.getMetadata().get("owner_key");
            if (stored != null && !String.valueOf(stored).isBlank()) legacy = String.valueOf(stored);
        } catch (RuntimeException ignored) { }
        int separator = legacy.indexOf(':');
        workspace = separator > 0 ? legacy.substring(0, separator) : "default";
        owner = separator > 0 && separator + 1 < legacy.length() ? legacy.substring(separator + 1) : legacy;
        String migratedWorkspace = workspace;
        String migratedOwner = owner;
        String migratedVisibility = visibility;
        database.transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE office_documents SET workspace_id=?,owner_user_id=?,visibility=? WHERE id=? AND (workspace_id IS NULL OR owner_user_id IS NULL)")) {
                update.setString(1, migratedWorkspace); update.setString(2, migratedOwner);
                update.setString(3, migratedVisibility); update.setString(4, document.getId()); update.executeUpdate();
            }
            return null;
        });
        document.setWorkspaceId(workspace); document.setOwnerUserId(owner); document.setVisibility(visibility);
        return new Security(workspace, owner, visibility, version);
    }

    private AclDecision aclDecision(String documentId, String userId, String role, String needed) {
        return database.transaction(connection -> {
            boolean allowed = false;
            boolean denied = false;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT permission,effect FROM document_permissions WHERE document_id=? AND ((principal_type='user' AND principal_id=?) OR (principal_type='role' AND principal_id=?))")) {
                statement.setString(1, documentId); statement.setString(2, userId); statement.setString(3, role == null ? "" : role);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String permission = rows.getString(1);
                        boolean deny = "deny".equals(rows.getString(2));
                        if ((deny && READ.equals(permission)) || grants(permission, needed)) {
                            if (deny) denied = true; else allowed = true;
                        }
                    }
                }
            }
            return new AclDecision(allowed, denied);
        });
    }

    private MemberState member(String workspaceId, String userId) {
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT role,status FROM workspace_members WHERE workspace_id=? AND user_id=?")) {
                statement.setString(1, workspaceId); statement.setString(2, userId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return new MemberState("", false);
                    return new MemberState(rows.getString(1), "active".equals(rows.getString(2)));
                }
            }
        });
    }

    private boolean workspaceMemberExists(String workspaceId, String userId) {
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM workspace_members WHERE workspace_id=? AND user_id=? AND status IN ('active','invited')")) {
                statement.setString(1, workspaceId); statement.setString(2, userId);
                try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
            }
        });
    }

    private static boolean grants(String actual, String needed) {
        if (MANAGE.equals(actual)) return true;
        if (WRITE.equals(actual)) return READ.equals(needed) || WRITE.equals(needed);
        return READ.equals(actual) && READ.equals(needed);
    }

    private static String normalizeVisibility(String value) {
        String visibility = lower(value);
        if (visibility.isBlank()) return Document.VISIBILITY_PRIVATE;
        if (!List.of(Document.VISIBILITY_PRIVATE, Document.VISIBILITY_WORKSPACE,
                Document.VISIBILITY_RESTRICTED).contains(visibility)) {
            throw new IllegalArgumentException("visibility 必须为 private、workspace 或 restricted");
        }
        return visibility;
    }

    private static String lower(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }

    public static RequestIdentity identity(String ownerKey) {
        String value = ownerKey == null || ownerKey.isBlank() ? "default:default" : ownerKey;
        int separator = value.indexOf(':');
        return separator > 0
                ? new RequestIdentity(value.substring(separator + 1), value.substring(0, separator))
                : new RequestIdentity(value, "default");
    }

    private record Security(String workspaceId, String ownerUserId, String visibility, long permissionVersion) { }
    private record MemberState(String role, boolean active) { }
    private record AclDecision(boolean allowed, boolean denied) { }
    private record AccessContext(Security security, boolean sameWorkspace, boolean active, String role,
                                 boolean documentOwner, boolean workspaceAdministrator) { }
}
