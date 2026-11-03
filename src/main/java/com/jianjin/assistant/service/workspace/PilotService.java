package com.jianjin.assistant.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.infrastructure.persistence.WorkspaceDatabase;
import com.jianjin.assistant.interfaces.http.security.RequestIdentity;
import com.jianjin.assistant.interfaces.http.security.RequestIdentityHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PilotService {
    private static final Set<String> TYPES = Set.of("report_started", "report_generated", "report_completed",
            "report_exported", "report_reused", "fact_error_reported", "feedback_submitted");

    public record EventRequest(String eventType, String reportId, Long durationMs, Integer editCount,
                               Integer factErrors, Boolean reusedPrior, Integer rating, String comment,
                               Map<String, Object> metadata) {}
    public record Dashboard(long generatedReports, long completedReports, long exportedReports,
                            double averageCompletionSeconds, double averageEditCount, double factErrorRate,
                            double reuseRate, double averageRating, long feedbackCount, List<String> recentComments) {}

    private final WorkspaceDatabase database;
    private final ObjectMapper json;
    private final WorkspaceAccessService access;

    public PilotService(WorkspaceDatabase database, ObjectMapper json, WorkspaceAccessService access) {
        this.database = database; this.json = json; this.access = access;
    }

    public void record(EventRequest request) { record(RequestIdentityHolder.current(), request); }

    public void record(RequestIdentity identity, EventRequest request) {
        validate(request);
        database.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO pilot_events(workspace_id,user_id,event_type,report_id,duration_ms,edit_count,fact_errors,reused_prior,rating,comment,metadata,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, identity.workspaceId()); ps.setString(2, identity.userId()); ps.setString(3, request.eventType());
                ps.setString(4, request.reportId() == null ? "" : request.reportId()); setLong(ps, 5, request.durationMs());
                setInt(ps, 6, request.editCount()); setInt(ps, 7, request.factErrors());
                if (request.reusedPrior() == null) ps.setNull(8, java.sql.Types.BOOLEAN); else ps.setBoolean(8, request.reusedPrior());
                setInt(ps, 9, request.rating()); ps.setString(10, request.comment() == null ? "" : request.comment().trim());
                ps.setString(11, json.writeValueAsString(request.metadata() == null ? Map.of() : request.metadata()));
                ps.setObject(12, timestamp(Instant.now())); ps.executeUpdate();
            }
            return null;
        });
    }

    public Dashboard dashboard() {
        WorkspaceAccessService.Member actor = access.requireAdministrator();
        return database.transaction(connection -> {
            long generated = 0, completed = 0, exported = 0, feedback = 0, reused = 0;
            long durationTotal = 0, durationCount = 0, editTotal = 0, editCount = 0, ratingTotal = 0, ratingCount = 0;
            List<String> comments = new ArrayList<>();
            Set<String> reportsWithErrors = new java.util.LinkedHashSet<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM pilot_events WHERE workspace_id=? ORDER BY created_at DESC")) {
                ps.setString(1, actor.workspaceId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String type = rs.getString("event_type");
                        if ("report_generated".equals(type)) { generated++; if (Boolean.TRUE.equals((Boolean) rs.getObject("reused_prior"))) reused++; }
                        if ("report_completed".equals(type)) completed++;
                        if ("report_exported".equals(type)) exported++;
                        if ("feedback_submitted".equals(type)) feedback++;
                        Long duration = (Long) rs.getObject("duration_ms"); if (duration != null && "report_completed".equals(type)) { durationTotal += duration; durationCount++; }
                        Integer edits = (Integer) rs.getObject("edit_count"); if (edits != null && "report_completed".equals(type)) { editTotal += edits; editCount++; }
                        Integer errors = (Integer) rs.getObject("fact_errors");
                        String reportId = rs.getString("report_id");
                        if (errors != null && errors > 0 && reportId != null && !reportId.isBlank()) reportsWithErrors.add(reportId);
                        Integer rating = (Integer) rs.getObject("rating"); if (rating != null) { ratingTotal += rating; ratingCount++; }
                        String comment = rs.getString("comment"); if (comment != null && !comment.isBlank() && comments.size() < 8) comments.add(comment);
                    }
                }
            }
            return new Dashboard(generated, completed, exported,
                    durationCount == 0 ? 0 : durationTotal / 1000.0 / durationCount,
                    editCount == 0 ? 0 : (double) editTotal / editCount,
                    completed == 0 ? 0 : (double) reportsWithErrors.size() / completed,
                    generated == 0 ? 0 : (double) reused / generated,
                    ratingCount == 0 ? 0 : (double) ratingTotal / ratingCount, feedback, comments);
        });
    }

    private static void validate(EventRequest request) {
        if (request == null || !TYPES.contains(request.eventType())) throw new IllegalArgumentException("不支持的试点事件类型");
        if (request.durationMs() != null && request.durationMs() < 0) throw new IllegalArgumentException("duration_ms 不能为负数");
        if (request.editCount() != null && request.editCount() < 0) throw new IllegalArgumentException("edit_count 不能为负数");
        if (request.factErrors() != null && request.factErrors() < 0) throw new IllegalArgumentException("fact_errors 不能为负数");
        if (request.factErrors() != null && request.factErrors() > 0 && (request.reportId() == null || request.reportId().isBlank())) {
            throw new IllegalArgumentException("记录事实错误时必须提供 report_id");
        }
        if (request.rating() != null && (request.rating() < 1 || request.rating() > 5)) throw new IllegalArgumentException("rating 必须为 1 到 5");
        if (request.comment() != null && request.comment().length() > 1000) throw new IllegalArgumentException("comment 不能超过 1000 字");
    }

    private static void setInt(PreparedStatement ps, int index, Integer value) throws Exception { if (value == null) ps.setNull(index, java.sql.Types.INTEGER); else ps.setInt(index, value); }
    private static void setLong(PreparedStatement ps, int index, Long value) throws Exception { if (value == null) ps.setNull(index, java.sql.Types.BIGINT); else ps.setLong(index, value); }
    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
