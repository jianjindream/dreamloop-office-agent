package com.jianjin.assistant.interfaces.http.security;

/** Authenticated request identity propagated into asynchronous tool execution. */
public record RequestIdentity(String userId, String workspaceId, String sessionId, String requestId) {
    public RequestIdentity(String userId, String workspaceId) {
        this(userId, workspaceId, "default", "");
    }

    public String ownerKey() { return workspaceId + ":" + userId; }

    public RequestIdentity withSessionId(String value) {
        return new RequestIdentity(userId, workspaceId,
                value == null || value.isBlank() ? "default" : value, requestId);
    }
}
