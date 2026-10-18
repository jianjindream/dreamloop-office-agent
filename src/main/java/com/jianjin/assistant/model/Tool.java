package com.jianjin.assistant.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class Tool {
    public static final String CONTEXT_OWNER_KEY = "__context_owner_key";
    public static final String CONTEXT_SESSION_ID = "__context_session_id";
    public static final String CONTEXT_REQUEST_ID = "__context_request_id";

    private String name;
    private String description;
    private List<ToolParam> parameters;
    private boolean mcp;
    private String endpoint;
    private boolean retryable;
    private boolean executionContextAware;
    private transient Function<Map<String, Object>, String> execute;

    public Tool() {}
    public Tool(String name, String description, List<ToolParam> parameters, Function<Map<String, Object>, String> execute) {
        this.name = name; this.description = description; this.parameters = parameters; this.execute = execute;
    }

    public Tool(String name, String description, List<ToolParam> parameters, boolean retryable,
                Function<Map<String, Object>, String> execute) {
        this(name, description, parameters, execute);
        this.retryable = retryable;
    }

    public Tool(String name, String description, List<ToolParam> parameters, boolean retryable,
                boolean executionContextAware, Function<Map<String, Object>, String> execute) {
        this(name, description, parameters, retryable, execute);
        this.executionContextAware = executionContextAware;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<ToolParam> getParameters() { return parameters; }
    public void setParameters(List<ToolParam> parameters) { this.parameters = parameters; }
    public boolean isMcp() { return mcp; }
    public void setMcp(boolean mcp) { this.mcp = mcp; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public boolean isRetryable() { return retryable; }
    public void setRetryable(boolean retryable) { this.retryable = retryable; }
    public boolean isExecutionContextAware() { return executionContextAware; }
    public void setExecutionContextAware(boolean executionContextAware) { this.executionContextAware = executionContextAware; }
    public Function<Map<String, Object>, String> getExecute() { return execute; }
    public void setExecute(Function<Map<String, Object>, String> execute) { this.execute = execute; }
}
