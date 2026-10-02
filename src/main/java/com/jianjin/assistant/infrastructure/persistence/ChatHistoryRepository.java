package com.jianjin.assistant.infrastructure.persistence;

import com.jianjin.assistant.infrastructure.platform.PostgresConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Instant;

@Repository
public class ChatHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryRepository.class);

    private final PostgresConnector pg;
    private final AtomicLong memoryIds = new AtomicLong();
    private final Map<String, List<Row>> memoryHistory = new LinkedHashMap<>();
    private final Map<String, SessionRow> memorySessions = new LinkedHashMap<>();

    public ChatHistoryRepository(PostgresConnector pg) {
        this.pg = pg;
    }

    public static class Row {
        public long id;
        public String role;
        public String content;
        public String createdAt;
    }

    public static class SessionRow {
        public String sessionId;
        public String title;
        public String createdAt;
        public String updatedAt;
        public int messageCount;
    }

    public synchronized long save(String userId, String sessionId, String role, String content) {
        Connection c = pg.connection();
        if (c == null) return saveMemory(userId, sessionId, role, content);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chat_history (user_id, session_id, role, content) VALUES (?, ?, ?, ?) RETURNING id")) {
            upsertSession(c, userId, sessionId, role, content);
            ps.setString(1, userId); ps.setString(2, sessionId); ps.setString(3, role); ps.setString(4, content);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        } catch (SQLException e) {
            log.warn("聊天记录保存失败: {}", e.getMessage());
        }
        return 0;
    }

    public synchronized List<Row> load(String userId, String sessionId, long afterId, int limit) {
        List<Row> rows = new ArrayList<>();
        Connection c = pg.connection();
        if (c == null) return loadMemory(userId, sessionId, afterId, limit);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, role, content, created_at::text FROM chat_history " +
                        "WHERE user_id = ? AND session_id = ? AND id > ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, userId); ps.setString(2, sessionId); ps.setLong(3, afterId); ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Row r = new Row();
                r.id = rs.getLong(1); r.role = rs.getString(2);
                r.content = rs.getString(3); r.createdAt = rs.getString(4);
                    rows.add(r);
                }
            }
        } catch (SQLException e) {
            log.warn("加载聊天记录失败: {}", e.getMessage());
        }
        Collections.reverse(rows);
        return rows;
    }

    public synchronized void deleteThrough(String userId, String sessionId, long throughId) {
        if (throughId <= 0) return;
        Connection c = pg.connection();
        if (c == null) {
            List<Row> rows = memoryHistory.get(scope(userId, sessionId));
            if (rows != null) rows.removeIf(row -> row.id <= throughId);
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM chat_history WHERE user_id = ? AND session_id = ? AND id <= ?")) {
            ps.setString(1, userId); ps.setString(2, sessionId); ps.setLong(3, throughId); ps.executeUpdate();
        } catch (SQLException e) { log.warn("聊天历史清理失败: {}", e.getMessage()); }
    }

    public synchronized List<SessionRow> listSessions(String userId) {
        Connection c = pg.connection();
        if (c == null) {
            List<SessionRow> out = new ArrayList<>();
            String prefix = userId + "\u0000";
            for (Map.Entry<String, SessionRow> entry : memorySessions.entrySet()) {
                if (entry.getKey().startsWith(prefix)) out.add(copy(entry.getValue()));
            }
            out.sort((a, b) -> b.updatedAt.compareTo(a.updatedAt));
            return out;
        }
        List<SessionRow> out = new ArrayList<>();
        String sql = "SELECT s.session_id,s.title,s.created_at::text,s.updated_at::text," +
                "COUNT(h.id) FROM chat_sessions s LEFT JOIN chat_history h ON h.user_id=s.user_id " +
                "AND h.session_id=s.session_id WHERE s.user_id=? GROUP BY s.session_id,s.title,s.created_at,s.updated_at " +
                "ORDER BY s.updated_at DESC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SessionRow row = new SessionRow();
                    row.sessionId=rs.getString(1); row.title=rs.getString(2); row.createdAt=rs.getString(3);
                    row.updatedAt=rs.getString(4); row.messageCount=rs.getInt(5); out.add(row);
                }
            }
        } catch (SQLException e) { log.warn("会话列表加载失败: {}", e.getMessage()); }
        return out;
    }

    public synchronized boolean renameSession(String userId, String sessionId, String title) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        Connection c = pg.connection();
        if (c == null) {
            SessionRow row = memorySessions.get(scope(userId, sessionId));
            if (row == null) return false;
            row.title = title.trim(); row.updatedAt = Instant.now().toString(); return true;
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE chat_sessions SET title=?,updated_at=NOW() WHERE user_id=? AND session_id=?")) {
            ps.setString(1,title.trim()); ps.setString(2,userId); ps.setString(3,sessionId); return ps.executeUpdate()>0;
        } catch (SQLException e) { throw new IllegalStateException("会话重命名失败", e); }
    }

    public synchronized boolean deleteSession(String userId, String sessionId) {
        Connection c = pg.connection();
        if (c == null) {
            boolean found = memorySessions.remove(scope(userId, sessionId)) != null;
            memoryHistory.remove(scope(userId, sessionId)); return found;
        }
        try {
            c.setAutoCommit(false);
            try (PreparedStatement h = c.prepareStatement("DELETE FROM chat_history WHERE user_id=? AND session_id=?");
                 PreparedStatement s = c.prepareStatement("DELETE FROM chat_sessions WHERE user_id=? AND session_id=?")) {
                h.setString(1,userId); h.setString(2,sessionId); h.executeUpdate();
                s.setString(1,userId); s.setString(2,sessionId); boolean found=s.executeUpdate()>0;
                c.commit(); return found;
            } catch (SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        } catch (SQLException e) { throw new IllegalStateException("会话删除失败", e); }
    }

    private long saveMemory(String userId, String sessionId, String role, String content) {
        String key = scope(userId, sessionId); Instant now = Instant.now();
        Row row = new Row(); row.id=memoryIds.incrementAndGet(); row.role=role; row.content=content; row.createdAt=now.toString();
        memoryHistory.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        SessionRow session = memorySessions.computeIfAbsent(key, ignored -> {
            SessionRow created = new SessionRow(); created.sessionId=sessionId; created.title="新对话";
            created.createdAt=now.toString(); return created;
        });
        if ("user".equals(role) && "新对话".equals(session.title)) session.title=deriveTitle(content);
        session.updatedAt=now.toString(); session.messageCount=memoryHistory.get(key).size();
        return row.id;
    }

    private List<Row> loadMemory(String userId, String sessionId, long afterId, int limit) {
        List<Row> source = memoryHistory.getOrDefault(scope(userId, sessionId), List.of());
        List<Row> out = new ArrayList<>();
        int start = Math.max(0, source.size() - Math.max(1, limit));
        for (int i=start;i<source.size();i++) if (source.get(i).id>afterId) out.add(copy(source.get(i)));
        return out;
    }

    private void upsertSession(Connection c, String userId, String sessionId, String role, String content) throws SQLException {
        String title = "user".equals(role) ? deriveTitle(content) : "新对话";
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO chat_sessions(user_id,session_id,title) VALUES(?,?,?) " +
                "ON CONFLICT(user_id,session_id) DO UPDATE SET updated_at=NOW(), title=CASE WHEN chat_sessions.title='新对话' AND EXCLUDED.title<>'新对话' THEN EXCLUDED.title ELSE chat_sessions.title END")) {
            ps.setString(1,userId); ps.setString(2,sessionId); ps.setString(3,title); ps.executeUpdate();
        }
    }

    private static String deriveTitle(String content) {
        String value = content == null ? "新对话" : content.replaceAll("\\s+", " ").trim();
        return value.isEmpty() ? "新对话" : value.substring(0, Math.min(40, value.length()));
    }
    private static String scope(String userId,String sessionId){return userId+"\u0000"+sessionId;}
    private static Row copy(Row in){Row r=new Row();r.id=in.id;r.role=in.role;r.content=in.content;r.createdAt=in.createdAt;return r;}
    private static SessionRow copy(SessionRow in){SessionRow r=new SessionRow();r.sessionId=in.sessionId;r.title=in.title;r.createdAt=in.createdAt;r.updatedAt=in.updatedAt;r.messageCount=in.messageCount;return r;}
}
