package com.jianjin.assistant.service.document;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UploadProgressService {
    private final Map<String, Progress> values = new ConcurrentHashMap<>();

    public void update(String ownerKey, String uploadId, int percent, String stage, String message) {
        if (uploadId == null || uploadId.isBlank()) return;
        values.put(key(ownerKey, uploadId), new Progress(uploadId, Math.max(0, Math.min(100, percent)), stage, message, Instant.now()));
    }

    public Progress get(String ownerKey, String uploadId) {
        Progress progress = values.get(key(ownerKey, uploadId));
        if (progress == null) throw new IllegalArgumentException("upload not found: " + uploadId);
        return progress;
    }

    private String key(String ownerKey, String uploadId) { return ownerKey + "\u0000" + uploadId; }
    public record Progress(String uploadId, int percent, String stage, String message, Instant updatedAt) {}
}
