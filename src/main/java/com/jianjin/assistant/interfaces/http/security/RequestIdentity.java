package com.jianjin.assistant.interfaces.http.security;

/** Authenticated request identity. The scoped user key is safe for persistence lookups. */
public record RequestIdentity(String userId, String workspaceId) {
    public String ownerKey() { return workspaceId + ":" + userId; }
}
