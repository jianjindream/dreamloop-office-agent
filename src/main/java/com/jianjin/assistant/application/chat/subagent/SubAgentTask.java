package com.jianjin.assistant.application.chat.subagent;

import java.util.LinkedHashMap;
import java.util.Map;

public class SubAgentTask {

    public final String id;
    public final String goal;
    public final String query;
    public final String ownerKey;
    public final Map<String, String> upstream;

    public SubAgentTask(String id, String goal, String query, Map<String, String> upstream) {
        this(id, goal, query, "default:default", upstream);
    }

    public SubAgentTask(String id, String goal, String query, String ownerKey, Map<String, String> upstream) {
        this.id = id == null ? "" : id;
        this.goal = goal == null ? "" : goal;
        this.query = query == null ? "" : query;
        this.ownerKey = ownerKey == null || ownerKey.isBlank() ? "default:default" : ownerKey;
        this.upstream = upstream == null ? new LinkedHashMap<>() : upstream;
    }
}
