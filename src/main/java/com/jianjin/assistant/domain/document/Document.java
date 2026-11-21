package com.jianjin.assistant.domain.document;

import java.time.Instant;

public class Document {

    public static final String STATUS_ACTIVE = "active";
    public static final String SOURCE_AGENT = "agent_generated";
    public static final String SOURCE_UPLOAD = "user_upload";
    public static final String VISIBILITY_PRIVATE = "private";
    public static final String VISIBILITY_WORKSPACE = "workspace";
    public static final String VISIBILITY_RESTRICTED = "restricted";

    private String id;
    private String title;
    private String docType;
    private String source;
    private String status = STATUS_ACTIVE;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    private int latestVersion;
    private String latestVersionId;
    private String workspaceId;
    private String ownerUserId;
    private String visibility = VISIBILITY_PRIVATE;
    private long permissionVersion = 1;
    /** Request-scoped response fields; they are not persisted. */
    private String accessLevel = "none";
    private boolean canManage;

    public Document() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public int getLatestVersion() { return latestVersion; }
    public void setLatestVersion(int latestVersion) { this.latestVersion = latestVersion; }
    public String getLatestVersionId() { return latestVersionId; }
    public void setLatestVersionId(String latestVersionId) { this.latestVersionId = latestVersionId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public long getPermissionVersion() { return permissionVersion; }
    public void setPermissionVersion(long permissionVersion) { this.permissionVersion = permissionVersion; }
    public String getAccessLevel() { return accessLevel; }
    public void setAccessLevel(String accessLevel) { this.accessLevel = accessLevel; }
    public boolean isCanManage() { return canManage; }
    public void setCanManage(boolean canManage) { this.canManage = canManage; }
}
