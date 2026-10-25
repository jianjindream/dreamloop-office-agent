package com.jianjin.assistant.infrastructure.persistence;

import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

@Repository
public class ChatHistoryRepository {
    private final WorkspaceDatabase database;
    public ChatHistoryRepository(WorkspaceDatabase database) { this.database = database; }

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
    public record ImportedMessage(String role, String content, long createdAt) {}

    public long save(String owner, String session, String role, String content) {
        validateMessage(role,content);
        return retryInsert(() -> database.transaction(c -> {
            ensureSession(c,owner,session,"user".equals(role) ? deriveTitle(content) : "新对话",Instant.now());
            try (PreparedStatement ps=c.prepareStatement("INSERT INTO chat_history(user_id,session_id,role,content) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1,owner); ps.setString(2,session); ps.setString(3,role); ps.setString(4,content); ps.executeUpdate();
                try(ResultSet rs=ps.getGeneratedKeys()) { if(rs.next()) return rs.getLong(1); }
                throw new SQLException("未返回消息 ID");
            }
        }));
    }

    /** Recent bounded context for model memory; product history uses page() instead. */
    public List<Row> load(String owner,String session,long afterId,int limit) {
        List<Row> result=query(owner,session,afterId,limit,false);
        Collections.reverse(result);
        return result;
    }
    /** Forward pagination returns earliest unseen messages without dropping old history. */
    public List<Row> page(String owner,String session,long afterId,int limit) { return query(owner,session,afterId,limit,true); }
    private List<Row> query(String owner,String session,long afterId,int limit,boolean forward) {
        return database.transaction(c -> {
            List<Row> result=new ArrayList<>();
            try(PreparedStatement ps=c.prepareStatement("SELECT id,role,content,created_at FROM chat_history WHERE user_id=? AND session_id=? AND id>? ORDER BY id " + (forward ? "ASC" : "DESC") + " LIMIT ?")) {
                ps.setString(1,owner); ps.setString(2,session); ps.setLong(3,Math.max(0,afterId)); ps.setInt(4,Math.max(1,Math.min(10000,limit)));
                try(ResultSet rs=ps.executeQuery()) { while(rs.next()) {
                    Row row=new Row(); row.id=rs.getLong(1); row.role=rs.getString(2); row.content=rs.getString(3);
                    row.createdAt=rs.getTimestamp(4).toInstant().toString(); result.add(row);
                }}
            }
            return result;
        });
    }
    public void deleteThrough(String owner,String session,long throughId) {
        if(throughId<=0) return;
        database.transaction(c -> {
            try(PreparedStatement ps=c.prepareStatement("DELETE FROM chat_history WHERE user_id=? AND session_id=? AND id<=?")) {
                ps.setString(1,owner); ps.setString(2,session); ps.setLong(3,throughId); ps.executeUpdate();
            }
            return null;
        });
    }
    public List<SessionRow> listSessions(String owner) { return listSessions(owner,""); }
    public List<SessionRow> listSessions(String owner,String query) {
        return database.transaction(c -> {
            List<SessionRow> result=new ArrayList<>();
            String filter=query==null ? "" : query.trim().toLowerCase(Locale.ROOT);
            String sql="SELECT s.session_id,s.title,s.created_at,s.updated_at,(SELECT COUNT(*) FROM chat_history h WHERE h.user_id=s.user_id AND h.session_id=s.session_id) AS message_count FROM chat_sessions s WHERE s.user_id=?";
            if(!filter.isBlank()) sql+=" AND (LOWER(s.title) LIKE ? ESCAPE '!' OR EXISTS(SELECT 1 FROM chat_history h WHERE h.user_id=s.user_id AND h.session_id=s.session_id AND LOWER(h.content) LIKE ? ESCAPE '!'))";
            sql+=" ORDER BY s.updated_at DESC,s.session_id";
            try(PreparedStatement ps=c.prepareStatement(sql)) {
                ps.setString(1,owner);
                if(!filter.isBlank()) {
                    String pattern="%"+filter.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
                    ps.setString(2,pattern); ps.setString(3,pattern);
                }
                try(ResultSet rs=ps.executeQuery()) { while(rs.next()) {
                    SessionRow row=new SessionRow(); row.sessionId=rs.getString(1); row.title=rs.getString(2);
                    row.createdAt=rs.getTimestamp(3).toInstant().toString(); row.updatedAt=rs.getTimestamp(4).toInstant().toString();
                    row.messageCount=rs.getInt(5); result.add(row);
                }}
            }
            return result;
        });
    }
    public boolean renameSession(String owner,String session,String title) {
        if(title==null || title.isBlank() || title.length()>200) throw new IllegalArgumentException("会话标题须为 1—200 字");
        return database.transaction(c -> {
            try(PreparedStatement ps=c.prepareStatement("UPDATE chat_sessions SET title=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND session_id=?")) {
                ps.setString(1,title.trim()); ps.setString(2,owner); ps.setString(3,session); return ps.executeUpdate()>0;
            }
        });
    }
    public boolean deleteSession(String owner,String session) {
        return database.transaction(c -> {
            try(PreparedStatement ps=c.prepareStatement("SELECT session_id FROM chat_sessions WHERE user_id=? AND session_id=? FOR UPDATE")) {
                ps.setString(1,owner); ps.setString(2,session); try(ResultSet rs=ps.executeQuery()) { if(!rs.next()) return false; }
            }
            try(PreparedStatement history=c.prepareStatement("DELETE FROM chat_history WHERE user_id=? AND session_id=?");
                PreparedStatement sessions=c.prepareStatement("DELETE FROM chat_sessions WHERE user_id=? AND session_id=?")) {
                history.setString(1,owner); history.setString(2,session); history.executeUpdate();
                sessions.setString(1,owner); sessions.setString(2,session); return sessions.executeUpdate()>0;
            }
        });
    }
    /** Existing server sessions win. Import is atomic and retrying never duplicates messages. */
    public boolean importSession(String owner,String session,String title,long createdAt,List<ImportedMessage> messages) {
        if(session==null || !session.matches("[A-Za-z0-9._-]{1,120}")) throw new IllegalArgumentException("会话 ID 不合法");
        if(messages==null || messages.isEmpty() || messages.size()>10000) throw new IllegalArgumentException("每个迁移会话须包含 1—10000 条消息");
        for(ImportedMessage message:messages) validateMessage(message.role(),message.content());
        return retryInsert(() -> database.transaction(c -> {
            try(PreparedStatement ps=c.prepareStatement("SELECT session_id FROM chat_sessions WHERE user_id=? AND session_id=?")) {
                ps.setString(1,owner); ps.setString(2,session); try(ResultSet rs=ps.executeQuery()) { if(rs.next()) return false; }
            }
            Instant created=createdAt>0 ? Instant.ofEpochMilli(createdAt) : Instant.now();
            ensureSession(c,owner,session,title==null || title.isBlank() ? deriveTitle(messages.get(0).content()) : title.substring(0,Math.min(200,title.length())),created);
            Instant updated=created;
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO chat_history(user_id,session_id,role,content,created_at) VALUES(?,?,?,?,?)")) {
                for(ImportedMessage message:messages) {
                    Instant time=message.createdAt()>0 ? Instant.ofEpochMilli(message.createdAt()) : created;
                    if(time.isAfter(updated)) updated=time;
                    ps.setString(1,owner); ps.setString(2,session); ps.setString(3,message.role()); ps.setString(4,message.content());
                    ps.setObject(5,time.atOffset(ZoneOffset.UTC)); ps.addBatch();
                }
                ps.executeBatch();
            }
            try(PreparedStatement ps=c.prepareStatement("UPDATE chat_sessions SET updated_at=? WHERE user_id=? AND session_id=?")) {
                ps.setObject(1,updated.atOffset(ZoneOffset.UTC)); ps.setString(2,owner); ps.setString(3,session); ps.executeUpdate();
            }
            return true;
        }));
    }
    private void ensureSession(Connection c,String owner,String session,String title,Instant created) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement("SELECT title FROM chat_sessions WHERE user_id=? AND session_id=? FOR UPDATE")) {
            ps.setString(1,owner); ps.setString(2,session);
            try(ResultSet rs=ps.executeQuery()) {
                if(rs.next()) {
                    String next="新对话".equals(rs.getString(1)) ? title : rs.getString(1);
                    try(PreparedStatement update=c.prepareStatement("UPDATE chat_sessions SET title=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND session_id=?")) {
                        update.setString(1,next); update.setString(2,owner); update.setString(3,session); update.executeUpdate();
                    }
                    return;
                }
            }
        }
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO chat_sessions(user_id,session_id,title,created_at,updated_at) VALUES(?,?,?,?,?)")) {
            ps.setString(1,owner); ps.setString(2,session); ps.setString(3,title);
            ps.setObject(4,created.atOffset(ZoneOffset.UTC)); ps.setObject(5,created.atOffset(ZoneOffset.UTC)); ps.executeUpdate();
        }
    }
    private static void validateMessage(String role,String content) {
        if(!"user".equals(role) && !"assistant".equals(role)) throw new IllegalArgumentException("不支持的消息角色");
        if(content==null || content.length()>1_000_000) throw new IllegalArgumentException("消息内容过长或为空");
    }
    private static String deriveTitle(String content) {
        String value=content.replaceAll("\\s+"," ").trim();
        return value.isBlank() ? "新对话" : value.substring(0,Math.min(40,value.length()));
    }
    private <T> T retryInsert(java.util.function.Supplier<T> operation) {
        try { return operation.get(); }
        catch(IllegalStateException e) {
            for(Throwable cause=e;cause!=null;cause=cause.getCause()) {
                if(cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) return operation.get();
            }
            throw e;
        }
    }
}
