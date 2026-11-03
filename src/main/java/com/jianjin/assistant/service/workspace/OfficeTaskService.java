package com.jianjin.assistant.service.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.domain.document.Ids;
import com.jianjin.assistant.infrastructure.persistence.WorkspaceDatabase;
import com.jianjin.assistant.interfaces.http.security.RequestIdentity;
import com.jianjin.assistant.interfaces.http.security.RequestIdentityHolder;
import com.jianjin.assistant.service.office.WeeklyReportService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class OfficeTaskService {
    public record Task(String id, String workspaceId, String userId, String taskType, String title, String status,
                       int progress, String message, Object result, String error, boolean cancelRequested,
                       Instant createdAt, Instant startedAt, Instant finishedAt, Instant updatedAt) {}

    private final WorkspaceDatabase database;
    private final ObjectMapper json;
    private final WeeklyReportService reports;
    private final OfficeConnectionService connections;
    private final WorkspaceAccessService access;
    private final PilotService pilot;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();

    public OfficeTaskService(WorkspaceDatabase database, ObjectMapper json, WeeklyReportService reports,
                             OfficeConnectionService connections, WorkspaceAccessService access, PilotService pilot) {
        this.database = database; this.json = json; this.reports = reports; this.connections = connections; this.access = access; this.pilot = pilot;
        recoverInterrupted();
    }

    public Task submitWeeklyReport(WeeklyReportService.GenerateRequest request) {
        access.requireEditor(); RequestIdentity identity = RequestIdentityHolder.current();
        Map<String, Object> payload = json.convertValue(request, new TypeReference<LinkedHashMap<String, Object>>(){});
        String title = (request.projectName() == null ? "" : request.projectName()) + "项目周报";
        return submit(identity, "weekly_report", title, payload, () -> {
            long started = System.currentTimeMillis();
            WeeklyReportService.Result result = reports.generate(identity.ownerKey(), request);
            boolean reused = request.documentIds() != null && request.documentIds().stream().anyMatch(this::weeklyReportDocument);
            pilot.record(identity, new PilotService.EventRequest("report_generated", result.documentId(),
                    System.currentTimeMillis() - started, null, null, reused, null, "", Map.of("task", true)));
            return result;
        });
    }

    public Task submitConnectionSync(String connectionId) {
        access.requireAdministrator(); RequestIdentity identity = RequestIdentityHolder.current();
        return submit(identity, "connection_sync", "同步办公平台连接", Map.of("connection_id", connectionId),
                () -> connections.syncSnapshot(identity.workspaceId(), connectionId));
    }

    public List<Task> list() {
        WorkspaceAccessService.Member member = access.current();
        return database.transaction(connection -> {
            List<Task> result = new ArrayList<>();
            String sql = member.administrator() ? "SELECT * FROM office_tasks WHERE workspace_id=? ORDER BY created_at DESC"
                    : "SELECT * FROM office_tasks WHERE workspace_id=? AND user_id=? ORDER BY created_at DESC";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, member.workspaceId()); if (!member.administrator()) ps.setString(2, member.userId());
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(read(rs)); }
            }
            return result;
        });
    }

    public Task get(String id) { return authorized(id); }

    public Task cancel(String id) {
        Task task = authorized(id);
        if (!List.of("queued", "running").contains(task.status())) throw new IllegalArgumentException("任务当前不能取消");
        database.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE office_tasks SET cancel_requested=TRUE,status='canceled',message='已取消',finished_at=?,updated_at=? WHERE id=? AND status IN ('queued','running')")) {
                ps.setObject(1, timestamp(Instant.now())); ps.setObject(2, timestamp(Instant.now())); ps.setString(3, id);
                if (ps.executeUpdate() == 0) throw new IllegalArgumentException("任务已结束，不能取消");
            }
            return null;
        });
        Future<?> future = futures.remove(id); if (future != null) future.cancel(true);
        return authorized(id);
    }

    public Task retry(String id) {
        Task previous = authorized(id);
        if (!List.of("failed", "canceled").contains(previous.status())) throw new IllegalArgumentException("仅失败或取消的任务可以重试");
        Map<String, Object> payload = payload(id);
        if ("weekly_report".equals(previous.taskType())) {
            return submitWeeklyReport(json.convertValue(payload, WeeklyReportService.GenerateRequest.class));
        }
        if ("connection_sync".equals(previous.taskType())) return submitConnectionSync(String.valueOf(payload.get("connection_id")));
        throw new IllegalArgumentException("不支持重试该任务类型");
    }

    private Task submit(RequestIdentity identity, String type, String title, Map<String, Object> payload, Runner runner) {
        String id = Ids.newId("task"); Instant now = Instant.now();
        database.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO office_tasks(id,workspace_id,user_id,task_type,title,status,progress,message,payload,result,error,cancel_requested,created_at,updated_at) VALUES(?,?,?,?,?,'queued',0,'等待执行',?,'{}','',FALSE,?,?)")) {
                ps.setString(1, id); ps.setString(2, identity.workspaceId()); ps.setString(3, identity.userId()); ps.setString(4, type); ps.setString(5, title);
                ps.setString(6, json.writeValueAsString(payload)); ps.setObject(7, timestamp(now)); ps.setObject(8, timestamp(now)); ps.executeUpdate();
            }
            return null;
        });
        Future<?> future = executor.submit(() -> run(id, runner)); futures.put(id, future);
        return raw(id);
    }

    private void run(String id, Runner runner) {
        update(id, "running", 10, "正在执行", null, "", false);
        try {
            if (cancelRequested(id)) return;
            update(id, "running", 45, "正在处理资料", null, "", false);
            Object result = runner.run();
            if (cancelRequested(id)) return;
            update(id, "succeeded", 100, "任务完成", result, "", true);
        } catch (Exception e) {
            if (!cancelRequested(id)) update(id, "failed", 100, "任务失败", null,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), true);
        } finally { futures.remove(id); }
    }

    private void update(String id, String status, int progress, String message, Object result, String error, boolean finished) {
        database.transaction(connection -> {
            String sql = finished
                    ? "UPDATE office_tasks SET status=?,progress=?,message=?,result=?,error=?,finished_at=?,updated_at=? WHERE id=? AND cancel_requested=FALSE"
                    : "UPDATE office_tasks SET status=?,progress=?,message=?,result=?,error=?,started_at=COALESCE(started_at,?),updated_at=? WHERE id=? AND cancel_requested=FALSE";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, status); ps.setInt(2, progress); ps.setString(3, message);
                ps.setString(4, result == null ? "{}" : json.writeValueAsString(result)); ps.setString(5, error == null ? "" : error);
                ps.setObject(6, timestamp(Instant.now())); ps.setObject(7, timestamp(Instant.now())); ps.setString(8, id); ps.executeUpdate();
            }
            return null;
        });
    }

    private Task authorized(String id) {
        Task task = raw(id); WorkspaceAccessService.Member member = access.current();
        if (!member.workspaceId().equals(task.workspaceId()) || (!member.administrator() && !member.userId().equals(task.userId()))) {
            throw new IllegalArgumentException("任务不存在");
        }
        return task;
    }

    private Task raw(String id) {
        return database.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM office_tasks WHERE id=?")) {
                ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("任务不存在"); return read(rs); }
            }
        });
    }

    private Task read(ResultSet rs) throws Exception {
        Object result = json.readValue(rs.getString("result"), Object.class);
        return new Task(rs.getString("id"), rs.getString("workspace_id"), rs.getString("user_id"), rs.getString("task_type"),
                rs.getString("title"), rs.getString("status"), rs.getInt("progress"), rs.getString("message"), result,
                rs.getString("error"), rs.getBoolean("cancel_requested"), instant(rs,"created_at"), instant(rs,"started_at"),
                instant(rs,"finished_at"), instant(rs,"updated_at"));
    }

    private Map<String, Object> payload(String id) {
        return database.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT payload FROM office_tasks WHERE id=?")) {
                ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) { rs.next(); return json.readValue(rs.getString(1), new TypeReference<LinkedHashMap<String,Object>>(){}); }
            }
        });
    }

    private boolean cancelRequested(String id) { return raw(id).cancelRequested(); }

    private boolean weeklyReportDocument(String id) {
        try { return WeeklyReportService.REPORT_TYPE.equals(reports.documentType(id)); } catch (Exception ignored) { return false; }
    }

    private void recoverInterrupted() {
        database.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE office_tasks SET status='failed',progress=100,message='任务因服务重启中断',error='服务重启后可重试',finished_at=?,updated_at=? WHERE status IN ('queued','running')")) {
                ps.setObject(1, timestamp(Instant.now())); ps.setObject(2, timestamp(Instant.now())); ps.executeUpdate();
            }
            return null;
        });
    }

    @PreDestroy public void close() { executor.shutdownNow(); }
    private static Instant instant(ResultSet rs, String name) throws Exception { java.sql.Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant(); }
    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    @FunctionalInterface private interface Runner { Object run() throws Exception; }
}
