package com.jianjin.assistant.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.infrastructure.platform.PostgresConnector;
import com.jianjin.assistant.service.memory.SessionSummary;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class SessionSummaryRepository {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PostgresConnector pg;
    private final Map<String, SessionSummary> memoryData = new ConcurrentHashMap<>();
    public SessionSummaryRepository(PostgresConnector pg) { this.pg = pg; }

    public synchronized SessionSummary load(String userId, String sessionId) {
        Connection c = pg.connection();
        if (c == null) return copy(memoryData.get(scope(userId, sessionId)));
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT summary, summarized_through_id FROM session_summaries WHERE user_id = ? AND session_id = ?")) {
            ps.setString(1, userId); ps.setString(2, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new SessionSummary();
                SessionSummary result = MAPPER.readValue(rs.getString(1), SessionSummary.class);
                result.summarizedThroughId = rs.getLong(2); return result;
            }
        } catch (Exception ignored) { return new SessionSummary(); }
    }

    public synchronized void save(String userId, String sessionId, SessionSummary summary) {
        Connection c = pg.connection();
        if (c == null) {
            memoryData.put(scope(userId, sessionId), copy(summary));
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO session_summaries (user_id, session_id, summary, summarized_through_id) VALUES (?, ?, ?::jsonb, ?) " +
                        "ON CONFLICT (user_id, session_id) DO UPDATE SET summary = EXCLUDED.summary, summarized_through_id = EXCLUDED.summarized_through_id, updated_at = NOW()")) {
            ps.setString(1, userId); ps.setString(2, sessionId); ps.setString(3, MAPPER.writeValueAsString(summary));
            ps.setLong(4, summary.summarizedThroughId); ps.executeUpdate();
        } catch (Exception ignored) { }
    }

    public synchronized void delete(String userId, String sessionId) {
        Connection c = pg.connection();
        if (c == null) {
            memoryData.remove(scope(userId, sessionId));
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM session_summaries WHERE user_id=? AND session_id=?")) {
            ps.setString(1,userId); ps.setString(2,sessionId); ps.executeUpdate();
        } catch (Exception ignored) { }
    }

    private static String scope(String userId, String sessionId) {
        return userId + "\u0000" + sessionId;
    }

    private static SessionSummary copy(SessionSummary source) {
        if (source == null) return new SessionSummary();
        try {
            return MAPPER.readValue(MAPPER.writeValueAsString(source), SessionSummary.class);
        } catch (Exception ignored) {
            return new SessionSummary();
        }
    }
}
