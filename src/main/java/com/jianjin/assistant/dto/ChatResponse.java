package com.jianjin.assistant.dto;

import com.jianjin.assistant.model.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResponse {
    @JsonProperty("task_id")
    private String taskId;
    private String query;
    private String answer;
    private String mode;
    private String intent;
    @JsonProperty("knowledge_scope")
    private String knowledgeScope;
    @JsonProperty("document_ids")
    private List<String> documentIds;
    private List<ReActStep> steps;
    @JsonProperty("tool_call")
    private ToolCallResult toolCall;
    @JsonProperty("search_results")
    private List<SearchResultDto> searchResults;
    private TaskState task;
    @JsonProperty("extracted_info")
    private String extractedInfo;
    @JsonProperty("short_term_count")
    private int shortTermCount;
    @JsonProperty("long_term_count")
    private int longTermCount;
    private Map<String, String> preferences;
    private Boolean interrupted;

    // getters/setters
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getKnowledgeScope() { return knowledgeScope; }
    public void setKnowledgeScope(String knowledgeScope) { this.knowledgeScope = knowledgeScope; }
    public List<String> getDocumentIds() { return documentIds; }
    public void setDocumentIds(List<String> documentIds) { this.documentIds = documentIds; }
    public List<ReActStep> getSteps() { return steps; }
    public void setSteps(List<ReActStep> steps) { this.steps = steps; }
    public ToolCallResult getToolCall() { return toolCall; }
    public void setToolCall(ToolCallResult toolCall) { this.toolCall = toolCall; }
    public List<SearchResultDto> getSearchResults() { return searchResults; }
    public void setSearchResults(List<SearchResultDto> searchResults) { this.searchResults = searchResults; }
    public TaskState getTask() { return task; }
    public void setTask(TaskState task) { this.task = task; }
    public String getExtractedInfo() { return extractedInfo; }
    public void setExtractedInfo(String extractedInfo) { this.extractedInfo = extractedInfo; }
    public int getShortTermCount() { return shortTermCount; }
    public void setShortTermCount(int shortTermCount) { this.shortTermCount = shortTermCount; }
    public int getLongTermCount() { return longTermCount; }
    public void setLongTermCount(int longTermCount) { this.longTermCount = longTermCount; }
    public Map<String, String> getPreferences() { return preferences; }
    public void setPreferences(Map<String, String> preferences) { this.preferences = preferences; }
    public Boolean getInterrupted() { return interrupted; }
    public void setInterrupted(Boolean interrupted) { this.interrupted = interrupted; }

    public static class SearchResultDto {
        private Chunk chunk;
        private double similarity;
        @JsonProperty("context_id") private long contextId;
        @JsonProperty("document_id") private String documentId;
        @JsonProperty("version_id") private String versionId;
        @JsonProperty("document_title") private String documentTitle;
        private Integer version;
        private String locator;
        private String excerpt;
        @JsonProperty("retrieval_source") private String retrievalSource;
        private boolean fallback;
        public SearchResultDto() {}
        public SearchResultDto(Chunk chunk, double similarity) { this.chunk = chunk; this.similarity = similarity; }
        public Chunk getChunk() { return chunk; }
        public void setChunk(Chunk chunk) { this.chunk = chunk; }
        public double getSimilarity() { return similarity; }
        public void setSimilarity(double similarity) { this.similarity = similarity; }
        public long getContextId() { return contextId; }
        public void setContextId(long contextId) { this.contextId = contextId; }
        public String getDocumentId() { return documentId; }
        public void setDocumentId(String documentId) { this.documentId = documentId; }
        public String getVersionId() { return versionId; }
        public void setVersionId(String versionId) { this.versionId = versionId; }
        public String getDocumentTitle() { return documentTitle; }
        public void setDocumentTitle(String documentTitle) { this.documentTitle = documentTitle; }
        public Integer getVersion() { return version; }
        public void setVersion(Integer version) { this.version = version; }
        public String getLocator() { return locator; }
        public void setLocator(String locator) { this.locator = locator; }
        public String getExcerpt() { return excerpt; }
        public void setExcerpt(String excerpt) { this.excerpt = excerpt; }
        public String getRetrievalSource() { return retrievalSource; }
        public void setRetrievalSource(String retrievalSource) { this.retrievalSource = retrievalSource; }
        public boolean isFallback() { return fallback; }
        public void setFallback(boolean fallback) { this.fallback = fallback; }
    }
}
