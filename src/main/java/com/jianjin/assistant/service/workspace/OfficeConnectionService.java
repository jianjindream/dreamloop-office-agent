package com.jianjin.assistant.service.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.domain.document.Ids;
import com.jianjin.assistant.infrastructure.persistence.WorkspaceDatabase;
import com.jianjin.assistant.interfaces.http.security.RequestIdentityHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class OfficeConnectionService {
    private static final Map<String, Provider> PROVIDERS = Map.of(
            "microsoft365", new Provider("microsoft365", "Microsoft 365", List.of("onedrive.read", "sharepoint.read")),
            "google_workspace", new Provider("google_workspace", "Google Workspace", List.of("drive.read", "docs.read")),
            "feishu", new Provider("feishu", "飞书", List.of("drive.read", "docs.read")),
            "dingtalk", new Provider("dingtalk", "钉钉", List.of("drive.read")),
            "wecom", new Provider("wecom", "企业微信", List.of("wedrive.read")),
            "manual_upload", new Provider("manual_upload", "手动上传", List.of("files.upload")));

    public record Provider(String id, String name, List<String> capabilities) {}
    public record Connection(String id, String workspaceId, String provider, String providerName, String displayName,
                             String status, String credentialRef, Map<String, Object> config, List<String> capabilities,
                             Instant lastTestedAt, Instant lastSyncAt, String createdBy, Instant createdAt, Instant updatedAt) {}
    public record TestResult(String status, String message, List<String> capabilities) {}

    private final WorkspaceDatabase database;
    private final ObjectMapper json;
    private final WorkspaceAccessService access;

    public OfficeConnectionService(WorkspaceDatabase database, ObjectMapper json, WorkspaceAccessService access) {
        this.database = database; this.json = json; this.access = access;
    }

    public List<Provider> providers() { return PROVIDERS.values().stream().sorted(java.util.Comparator.comparing(Provider::name)).toList(); }

    public List<Connection> list() {
        String workspace = RequestIdentityHolder.current().workspaceId();
        return database.transaction(connection -> {
            List<Connection> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM office_connections WHERE workspace_id=? ORDER BY updated_at DESC")) {
                ps.setString(1, workspace); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(read(rs)); }
            }
            return result;
        });
    }

    public Connection create(String providerId, String displayName, String credentialRef, Map<String, Object> config) {
        WorkspaceAccessService.Member actor = access.requireAdministrator();
        Provider provider = provider(providerId);
        Map<String, Object> safeConfig = safeConfig(config);
        String reference = validateCredentialRef(provider.id(), credentialRef);
        String id = Ids.newId("conn"); Instant now = Instant.now();
        return database.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO office_connections(id,workspace_id,provider,display_name,status,credential_ref,config,capabilities,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id); ps.setString(2, actor.workspaceId()); ps.setString(3, provider.id());
                ps.setString(4, displayName == null || displayName.isBlank() ? provider.name() : displayName.trim());
                ps.setString(5, "manual_upload".equals(provider.id()) ? "connected" : "authorization_required");
                ps.setString(6, reference); ps.setString(7, json.writeValueAsString(safeConfig)); ps.setString(8, json.writeValueAsString(provider.capabilities()));
                ps.setString(9, actor.userId()); ps.setObject(10, timestamp(now)); ps.setObject(11, timestamp(now)); ps.executeUpdate();
            }
            return find(connection, actor.workspaceId(), id);
        });
    }

    public Connection update(String id, String displayName, String credentialRef, Map<String, Object> config) {
        WorkspaceAccessService.Member actor = access.requireAdministrator();
        return database.transaction(connection -> {
            Connection current = required(connection, actor.workspaceId(), id);
            String reference = credentialRef == null || credentialRef.isBlank() ? current.credentialRef() : validateCredentialRef(current.provider(), credentialRef);
            Map<String, Object> safe = config == null ? current.config() : safeConfig(config);
            try (PreparedStatement ps = connection.prepareStatement("UPDATE office_connections SET display_name=?,credential_ref=?,config=?,status=?,updated_at=? WHERE workspace_id=? AND id=?")) {
                ps.setString(1, displayName == null || displayName.isBlank() ? current.displayName() : displayName.trim());
                ps.setString(2, reference); ps.setString(3, json.writeValueAsString(safe));
                ps.setString(4, "manual_upload".equals(current.provider()) ? "connected" : "authorization_required");
                ps.setObject(5, timestamp(Instant.now())); ps.setString(6, actor.workspaceId()); ps.setString(7, id); ps.executeUpdate();
            }
            return find(connection, actor.workspaceId(), id);
        });
    }

    public TestResult test(String id) {
        WorkspaceAccessService.Member actor = access.requireAdministrator();
        return database.transaction(connection -> {
            Connection current = required(connection, actor.workspaceId(), id);
            boolean ready = "manual_upload".equals(current.provider()) || credentialAvailable(current.credentialRef());
            String status = ready ? "connected" : "authorization_required";
            Instant now = Instant.now();
            try (PreparedStatement ps = connection.prepareStatement("UPDATE office_connections SET status=?,last_tested_at=?,updated_at=? WHERE workspace_id=? AND id=?")) {
                ps.setString(1, status); ps.setObject(2, timestamp(now)); ps.setObject(3, timestamp(now));
                ps.setString(4, actor.workspaceId()); ps.setString(5, id); ps.executeUpdate();
            }
            String message = ready ? "凭证引用和连接配置有效" : "凭证引用尚未解析，请在部署环境配置对应密钥";
            return new TestResult(status, message, current.capabilities());
        });
    }

    public Map<String, Object> syncSnapshot(String workspaceId, String id) {
        return database.transaction(connection -> {
            Connection current = required(connection, workspaceId, id);
            if (!"connected".equals(current.status())) throw new IllegalArgumentException("连接尚未通过配置验证");
            Instant now = Instant.now();
            try (PreparedStatement ps = connection.prepareStatement("UPDATE office_connections SET last_sync_at=?,updated_at=? WHERE workspace_id=? AND id=?")) {
                ps.setObject(1, timestamp(now)); ps.setObject(2, timestamp(now)); ps.setString(3, workspaceId); ps.setString(4, id); ps.executeUpdate();
            }
            return Map.of("connection_id", id, "provider", current.provider(), "capabilities", current.capabilities(),
                    "checked_at", now.toString(), "imported_count", 0,
                    "message", "连接状态已同步；远端文件导入需由对应平台授权范围驱动");
        });
    }

    public void delete(String id) {
        WorkspaceAccessService.Member actor = access.requireAdministrator();
        database.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM office_connections WHERE workspace_id=? AND id=?")) {
                ps.setString(1, actor.workspaceId()); ps.setString(2, id); if (ps.executeUpdate() == 0) throw new IllegalArgumentException("连接不存在");
            }
            return null;
        });
    }

    private Connection required(java.sql.Connection connection, String workspace, String id) throws Exception {
        Connection result = find(connection, workspace, id); if (result == null) throw new IllegalArgumentException("连接不存在"); return result;
    }

    private Connection find(java.sql.Connection connection, String workspace, String id) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM office_connections WHERE workspace_id=? AND id=?")) {
            ps.setString(1, workspace); ps.setString(2, id); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? read(rs) : null; }
        }
    }

    private Connection read(ResultSet rs) throws Exception {
        String providerId = rs.getString("provider"); Provider provider = provider(providerId);
        Map<String, Object> config = json.readValue(rs.getString("config"), new TypeReference<LinkedHashMap<String, Object>>(){});
        List<String> capabilities = json.readValue(rs.getString("capabilities"), new TypeReference<ArrayList<String>>(){});
        return new Connection(rs.getString("id"), rs.getString("workspace_id"), providerId, provider.name(),
                rs.getString("display_name"), rs.getString("status"), rs.getString("credential_ref"), config, capabilities,
                instant(rs, "last_tested_at"), instant(rs, "last_sync_at"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static Provider provider(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT); Provider provider = PROVIDERS.get(id);
        if (provider == null) throw new IllegalArgumentException("不支持的办公平台"); return provider;
    }

    private static String validateCredentialRef(String provider, String value) {
        String ref = value == null ? "" : value.trim();
        if ("manual_upload".equals(provider)) return "";
        if (!ref.isBlank() && !ref.matches("(?:env|vault|secret):[A-Za-z0-9._/-]{1,160}")) {
            throw new IllegalArgumentException("credential_ref 只能使用 env:、vault: 或 secret: 引用，不能直接保存令牌");
        }
        return ref;
    }

    private static Map<String, Object> safeConfig(Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (config == null) return result;
        config.forEach((key, value) -> {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("token") || lower.contains("secret") || lower.contains("password") || lower.contains("api_key")) {
                throw new IllegalArgumentException("连接配置不能包含明文凭证，请使用 credential_ref");
            }
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) result.put(key, value);
            else throw new IllegalArgumentException("连接配置只支持文本、数字和布尔值");
        });
        return result;
    }

    private static boolean credentialAvailable(String ref) {
        if (ref == null || ref.isBlank()) return false;
        if (ref.startsWith("env:")) { String value = System.getenv(ref.substring(4)); return value != null && !value.isBlank(); }
        return true;
    }

    private static Instant instant(ResultSet rs, String name) throws Exception { java.sql.Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant(); }
    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
