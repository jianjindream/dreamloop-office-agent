package com.jianjin.assistant.application.chat;

import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.model.Snapshot;
import com.jianjin.assistant.model.TaskState;
import com.jianjin.assistant.model.TaskStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SnapshotManager {

    private static final ObjectMapper M = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String DEFAULT_OWNER = "default:default";
    private static final int MAX_SNAPSHOTS_PER_OWNER = 100;

    private final InfrastructureService infra;
    private final Map<String, List<Snapshot>> snapshotsByOwner = new ConcurrentHashMap<>();

    public SnapshotManager(InfrastructureService infra) {
        this.infra = infra;
    }

    /** 拍一份快照（深拷贝 TaskState 后写入内存与 PG）。 */
    public void save(TaskState currentTask) {
        save(DEFAULT_OWNER, currentTask);
    }

    public void save(String ownerKey, TaskState currentTask) {
        if (currentTask == null) return;
        try {
            String json = M.writeValueAsString(currentTask);
            TaskState copy = M.readValue(json, TaskState.class);
            List<Snapshot> snapshots = ownerSnapshots(ownerKey);
            synchronized (snapshots) {
                snapshots.add(new Snapshot(copy,
                        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
                while (snapshots.size() > MAX_SNAPSHOTS_PER_OWNER) snapshots.remove(0);
            }
            infra.saveSnapshot(currentTask.getTaskId(), json);
        } catch (Exception ignored) {}
    }

    /** 清空内存快照（用于新任务开始时）。 */
    public void clear() { clear(DEFAULT_OWNER); }

    public void clear(String ownerKey) { ownerSnapshots(ownerKey).clear(); }

    /** 副本（避免外部修改）。 */
    public List<Snapshot> snapshots() { return snapshots(DEFAULT_OWNER); }

    public List<Snapshot> snapshots(String ownerKey) {
        List<Snapshot> snapshots = ownerSnapshots(ownerKey);
        synchronized (snapshots) {
            return new ArrayList<>(snapshots);
        }
    }

    private List<Snapshot> ownerSnapshots(String ownerKey) {
        String key = ownerKey == null || ownerKey.isBlank() ? DEFAULT_OWNER : ownerKey;
        return snapshotsByOwner.computeIfAbsent(key,
                ignored -> Collections.synchronizedList(new ArrayList<>()));
    }

    /** 中断时构造摘要消息：已完成 X/Y 步 ... */
    public static String buildInterruptMessage(TaskState task) {
        int done = 0;
        List<String> doneDesc = new ArrayList<>();
        List<String> pendingDesc = new ArrayList<>();
        for (TaskStep s : task.getSteps()) {
            if (TaskStep.DONE.equals(s.getStatus())) {
                done++;
                String r = s.getResult() != null && s.getResult().length() > 30
                        ? s.getResult().substring(0, 30) + "..." : s.getResult();
                doneDesc.add(s.getId() + "." + s.getToolName() + "→" + r);
            } else {
                pendingDesc.add(s.getId() + "." + s.getToolName());
            }
        }
        StringBuilder msg = new StringBuilder("已完成 " + done + "/" + task.getSteps().size() + " 步");
        if (!doneDesc.isEmpty()) msg.append("：").append(String.join("；", doneDesc));
        if (!pendingDesc.isEmpty()) msg.append("；未执行：").append(String.join("、", pendingDesc));
        return msg.toString();
    }
}
