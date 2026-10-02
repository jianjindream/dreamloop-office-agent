package com.jianjin.assistant.application.chat;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks independent in-flight generations so one client cannot cancel another task. */
@Service
public class ChatTaskRegistry {
    private final Map<String, TaskHandle> tasks = new ConcurrentHashMap<>();

    public TaskHandle create(String ownerKey) {
        String id = "task_" + UUID.randomUUID().toString().replace("-", "");
        TaskHandle handle = new TaskHandle(id, ownerKey, Instant.now(), new AtomicBoolean(false));
        tasks.put(id, handle);
        return handle;
    }

    public boolean cancel(String taskId, String ownerKey) {
        TaskHandle handle = tasks.get(taskId);
        if (handle == null || !handle.ownerKey().equals(ownerKey)) return false;
        handle.cancelled().set(true);
        return true;
    }

    public void complete(String taskId) { tasks.remove(taskId); }

    public record TaskHandle(String taskId, String ownerKey, Instant createdAt, AtomicBoolean cancelled) {}
}
