package com.jianjin.assistant.service.memory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persisted, bounded digest of chat turns that have left the raw-message window. */
public class SessionSummary {
    public String goal = "";
    public List<String> constraints = new ArrayList<>();
    public List<String> decisions = new ArrayList<>();
    public List<String> completed = new ArrayList<>();
    public List<String> pending = new ArrayList<>();
    public Map<String, String> entities = new LinkedHashMap<>();
    public List<String> openQuestions = new ArrayList<>();
    public List<String> contextNotes = new ArrayList<>();
    public long summarizedThroughId;

    @JsonIgnore
    public boolean isEmpty() {
        return goal.isBlank() && constraints.isEmpty() && decisions.isEmpty() && completed.isEmpty()
                && pending.isEmpty() && entities.isEmpty() && openQuestions.isEmpty() && contextNotes.isEmpty();
    }
}
