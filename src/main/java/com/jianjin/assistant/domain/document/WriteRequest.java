package com.jianjin.assistant.domain.document;

import java.util.LinkedHashMap;
import java.util.Map;

public class WriteRequest {

    private String documentId = "";
    private String title;
    private String docType;
    private String source;
    private String createdBy;
    private String contentMd;
    private String summary = "";
    private String status = Document.STATUS_ACTIVE;
    private Integer baseVersion;
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private String workspaceId = "";
    private String ownerUserId = "";
    private String visibility = Document.VISIBILITY_PRIVATE;

    public WriteRequest() {}

    public WriteRequest(String title, String docType, String source, String createdBy,
                        String contentMd, String summary, Map<String, Object> metadata) {
        this.title = title;
        this.docType = docType;
        this.source = source;
        this.createdBy = createdBy;
        this.contentMd = contentMd;
        if (summary != null) this.summary = summary;
        if (metadata != null) this.metadata = metadata;
    }

    /** 归一化：去前后空格 + 默认值（doc_type=note / source=agent_generated / created_by=agent）。 */
    public static WriteRequest normalize(WriteRequest in) {
        WriteRequest r = in == null ? new WriteRequest() : in;
        r.title = trim(r.title);
        r.docType = trim(r.docType);
        r.source = trim(r.source);
        r.createdBy = trim(r.createdBy);
        r.contentMd = trim(r.contentMd);
        r.summary = trim(r.summary);
        r.workspaceId = trim(r.workspaceId);
        r.ownerUserId = trim(r.ownerUserId);
        r.visibility = trim(r.visibility).toLowerCase();
        if (r.docType.isEmpty()) r.docType = "note";
        if (r.source.isEmpty()) r.source = Document.SOURCE_AGENT;
        if (r.createdBy.isEmpty()) r.createdBy = "agent";
        if (r.metadata == null) r.metadata = new LinkedHashMap<>();
        if (!java.util.List.of(Document.VISIBILITY_PRIVATE, Document.VISIBILITY_WORKSPACE,
                Document.VISIBILITY_RESTRICTED).contains(r.visibility)) r.visibility = Document.VISIBILITY_PRIVATE;
        return r;
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    public String getDocumentId() { return documentId; }
    public String getStatus() { return status; }
    public Integer getBaseVersion() { return baseVersion; }
    public void setBaseVersion(Integer baseVersion) { this.baseVersion = baseVersion; }
    public void setStatus(String status) { this.status = "draft".equals(status) ? "draft" : Document.STATUS_ACTIVE; }
    public void setDocumentId(String documentId) { this.documentId = documentId == null ? "" : documentId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary == null ? "" : summary; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : metadata;
    }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId == null ? "" : workspaceId; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId == null ? "" : ownerUserId; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility == null ? Document.VISIBILITY_PRIVATE : visibility; }
}
