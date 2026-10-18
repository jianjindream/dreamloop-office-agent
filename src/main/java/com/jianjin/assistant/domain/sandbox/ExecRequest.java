package com.jianjin.assistant.domain.sandbox;

import java.time.Duration;

public class ExecRequest {
    private String command;
    /** 0 表示使用 Sandbox 默认值 */
    private Duration timeout;
    /** 对 Warn 级别命令的二次确认标记 */
    private boolean confirm;
    /** Sandbox 签发、与命令绑定且只能使用一次的确认令牌。 */
    private String confirmationToken;
    private String userId = "default";
    private String workspaceId = "default";
    private String sessionId = "default";
    private String requestId = "";

    public ExecRequest() {}

    public ExecRequest(String command) {
        this.command = command;
    }

    public ExecRequest(String command, Duration timeout, boolean confirm) {
        this.command = command;
        this.timeout = timeout;
        this.confirm = confirm;
    }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public boolean isConfirm() { return confirm; }
    public void setConfirm(boolean confirm) { this.confirm = confirm; }
    public String getConfirmationToken() { return confirmationToken; }
    public void setConfirmationToken(String confirmationToken) { this.confirmationToken = confirmationToken; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = safe(userId, "default"); }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = safe(workspaceId, "default"); }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = safe(sessionId, "default"); }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = safe(requestId, ""); }

    public String approvalScope() { return workspaceId + "\n" + userId + "\n" + sessionId; }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
