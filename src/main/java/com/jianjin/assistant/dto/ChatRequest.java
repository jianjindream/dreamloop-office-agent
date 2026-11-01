package com.jianjin.assistant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class ChatRequest {
    private String message;
    @JsonProperty("use_rag")
    private boolean useRag;
    @JsonProperty("selected_tools")
    private List<String> selectedTools;
    private boolean explicit;
    @JsonProperty("user_id")
    private String userId;
    @JsonProperty("session_id")
    private String sessionId;
    @JsonProperty("knowledge_scope")
    private String knowledgeScope;
    @JsonProperty("document_ids")
    private List<String> documentIds;
    @JsonIgnore
    private List<String> allowedToolKeys;
    @JsonIgnore
    private Set<String> allowedDocHashes;
    @JsonIgnore
    private String requestId;

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public boolean isUseRag() { return useRag; }
    public void setUseRag(boolean useRag) { this.useRag = useRag; }
    public List<String> getSelectedTools() { return selectedTools; }
    public void setSelectedTools(List<String> selectedTools) { this.selectedTools = selectedTools; }
    public boolean isExplicit() { return explicit; }
    public void setExplicit(boolean explicit) { this.explicit = explicit; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getKnowledgeScope() { return knowledgeScope; }
    public void setKnowledgeScope(String knowledgeScope) { this.knowledgeScope = knowledgeScope; }
    public List<String> getDocumentIds() { return documentIds; }
    public void setDocumentIds(List<String> documentIds) { this.documentIds = documentIds; }
    public List<String> getAllowedToolKeys() { return allowedToolKeys; }
    public void setAllowedToolKeys(List<String> allowedToolKeys) { this.allowedToolKeys = allowedToolKeys; }
    public Set<String> getAllowedDocHashes() { return allowedDocHashes; }
    public void setAllowedDocHashes(Set<String> allowedDocHashes) { this.allowedDocHashes = allowedDocHashes; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
