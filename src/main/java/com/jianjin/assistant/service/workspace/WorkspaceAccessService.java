package com.jianjin.assistant.service.workspace;

import com.jianjin.assistant.infrastructure.persistence.WorkspaceDatabase;
import com.jianjin.assistant.interfaces.http.error.ApiException;
import com.jianjin.assistant.interfaces.http.security.RequestIdentity;
import com.jianjin.assistant.interfaces.http.security.RequestIdentityHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class WorkspaceAccessService {
    public static final String OWNER = "owner";
    public static final String ADMIN = "admin";
    public static final String EDITOR = "editor";
    public static final String VIEWER = "viewer";

    public record Member(String workspaceId, String userId, String displayName, String email,
                         String role, String status, String invitedBy, Instant createdAt, Instant updatedAt) {
        public boolean administrator() { return OWNER.equals(role) || ADMIN.equals(role); }
        public boolean editor() { return administrator() || EDITOR.equals(role); }
        public boolean isAdministrator() { return administrator(); }
        public boolean isEditor() { return editor(); }
    }

    private final WorkspaceDatabase database;

    public WorkspaceAccessService(WorkspaceDatabase database) { this.database = database; }

    public Member current() { return ensureMember(RequestIdentityHolder.current()); }

    public Member ensureMember(RequestIdentity identity) {
        return database.transaction(connection -> {
            Member existing = find(connection, identity.workspaceId(), identity.userId());
            if (existing != null) {
                if ("invited".equals(existing.status())) {
                    try (PreparedStatement ps = connection.prepareStatement("UPDATE workspace_members SET status='active',updated_at=? WHERE workspace_id=? AND user_id=?")) {
                        ps.setObject(1, timestamp(Instant.now())); ps.setString(2, identity.workspaceId()); ps.setString(3, identity.userId()); ps.executeUpdate();
                    }
                    return new Member(existing.workspaceId(), existing.userId(), existing.displayName(), existing.email(),
                            existing.role(), "active", existing.invitedBy(), existing.createdAt(), Instant.now());
                }
                if (!"active".equals(existing.status())) throw forbidden("成员账号已停用");
                return existing;
            }
            int count;
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM workspace_members WHERE workspace_id=?")) {
                ps.setString(1, identity.workspaceId()); try (ResultSet rs = ps.executeQuery()) { rs.next(); count = rs.getInt(1); }
            }
            if (count > 0) throw forbidden("你尚未加入该工作区，请联系管理员邀请");
            Instant now = Instant.now();
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO workspace_members(workspace_id,user_id,display_name,email,role,status,invited_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, identity.workspaceId()); ps.setString(2, identity.userId()); ps.setString(3, identity.userId());
                ps.setString(4, ""); ps.setString(5, OWNER); ps.setString(6, "active"); ps.setString(7, identity.userId());
                ps.setObject(8, timestamp(now)); ps.setObject(9, timestamp(now)); ps.executeUpdate();
            }
            return new Member(identity.workspaceId(), identity.userId(), identity.userId(), "", OWNER, "active", identity.userId(), now, now);
        });
    }

    public List<Member> list() {
        Member actor = requireAdministrator();
        return database.transaction(connection -> {
            List<Member> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM workspace_members WHERE workspace_id=? ORDER BY created_at")) {
                ps.setString(1, actor.workspaceId()); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(read(rs)); }
            }
            return result;
        });
    }

    public Member invite(String userId, String displayName, String email, String role) {
        Member actor = requireAdministrator();
        String normalizedUser = requiredId(userId);
        String normalizedRole = normalizeRole(role);
        if (OWNER.equals(normalizedRole)) throw new IllegalArgumentException("不能直接邀请为所有者");
        if (ADMIN.equals(normalizedRole) && !OWNER.equals(actor.role())) throw forbidden("只有所有者可以邀请管理员");
        return database.transaction(connection -> {
            if (find(connection, actor.workspaceId(), normalizedUser) != null) throw new IllegalArgumentException("该成员已存在");
            Instant now = Instant.now();
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO workspace_members(workspace_id,user_id,display_name,email,role,status,invited_by,created_at,updated_at) VALUES(?,?,?,?,?,'invited',?,?,?)")) {
                ps.setString(1, actor.workspaceId()); ps.setString(2, normalizedUser);
                ps.setString(3, blankDefault(displayName, normalizedUser)); ps.setString(4, email == null ? "" : email.trim());
                ps.setString(5, normalizedRole); ps.setString(6, actor.userId()); ps.setObject(7, timestamp(now)); ps.setObject(8, timestamp(now)); ps.executeUpdate();
            }
            return find(connection, actor.workspaceId(), normalizedUser);
        });
    }

    public Member update(String userId, String role, String status) {
        Member actor = requireAdministrator();
        String normalizedRole = normalizeRole(role);
        String normalizedStatus = normalizeStatus(status);
        return database.transaction(connection -> {
            Member target = find(connection, actor.workspaceId(), userId);
            if (target == null) throw new IllegalArgumentException("成员不存在");
            if ((OWNER.equals(target.role()) || OWNER.equals(normalizedRole)) && !OWNER.equals(actor.role())) throw forbidden("只有所有者可以变更所有者角色");
            if (OWNER.equals(target.role()) && (!OWNER.equals(normalizedRole) || !"active".equals(normalizedStatus)) && ownerCount(connection, actor.workspaceId()) <= 1) {
                throw new IllegalArgumentException("工作区必须保留至少一名有效所有者");
            }
            try (PreparedStatement ps = connection.prepareStatement("UPDATE workspace_members SET role=?,status=?,updated_at=? WHERE workspace_id=? AND user_id=?")) {
                ps.setString(1, normalizedRole); ps.setString(2, normalizedStatus); ps.setObject(3, timestamp(Instant.now()));
                ps.setString(4, actor.workspaceId()); ps.setString(5, userId); ps.executeUpdate();
            }
            return find(connection, actor.workspaceId(), userId);
        });
    }

    public void remove(String userId) {
        Member actor = requireAdministrator();
        database.transaction(connection -> {
            Member target = find(connection, actor.workspaceId(), userId);
            if (target == null) throw new IllegalArgumentException("成员不存在");
            if (OWNER.equals(target.role()) && ownerCount(connection, actor.workspaceId()) <= 1) throw new IllegalArgumentException("不能移除最后一名所有者");
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM workspace_members WHERE workspace_id=? AND user_id=?")) {
                ps.setString(1, actor.workspaceId()); ps.setString(2, userId); ps.executeUpdate();
            }
            return null;
        });
    }

    public Member requireAdministrator() {
        Member member = current();
        if (!member.administrator()) throw forbidden("需要工作区管理员权限");
        return member;
    }

    public Member requireEditor() {
        Member member = current();
        if (!member.editor()) throw forbidden("当前角色为只读成员，不能修改工作区内容");
        return member;
    }

    public boolean administrator() { return current().administrator(); }

    private static Member find(java.sql.Connection connection, String workspace, String user) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM workspace_members WHERE workspace_id=? AND user_id=?")) {
            ps.setString(1, workspace); ps.setString(2, user);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? read(rs) : null; }
        }
    }

    private static Member read(ResultSet rs) throws Exception {
        return new Member(rs.getString("workspace_id"), rs.getString("user_id"), rs.getString("display_name"),
                rs.getString("email"), rs.getString("role"), rs.getString("status"), rs.getString("invited_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static int ownerCount(java.sql.Connection connection, String workspace) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM workspace_members WHERE workspace_id=? AND role='owner' AND status='active'")) {
            ps.setString(1, workspace); try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static String normalizeRole(String value) {
        String role = value == null ? VIEWER : value.trim().toLowerCase(Locale.ROOT);
        if (!List.of(OWNER, ADMIN, EDITOR, VIEWER).contains(role)) throw new IllegalArgumentException("role 必须为 owner、admin、editor 或 viewer");
        return role;
    }

    private static String normalizeStatus(String value) {
        String status = value == null ? "active" : value.trim().toLowerCase(Locale.ROOT);
        if (!List.of("active", "invited", "suspended").contains(status)) throw new IllegalArgumentException("status 不合法");
        return status;
    }

    private static String requiredId(String value) {
        String result = value == null ? "" : value.trim();
        if (!result.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("user_id 格式不正确");
        return result;
    }

    private static String blankDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static ApiException forbidden(String message) { return new ApiException("WORKSPACE_FORBIDDEN", message, HttpStatus.FORBIDDEN); }
}
