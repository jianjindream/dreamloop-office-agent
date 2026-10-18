package com.jianjin.assistant.application.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StreamEvent {

    public final String type;
    public final Object data;

    public StreamEvent(String type, Object data) {
        this.type = type;
        this.data = data;
    }

    public String type() { return type; }
    public Object data() { return data; }

    public static StreamEvent start(String message) {
        return new StreamEvent("start", Map.of("message", message == null ? "" : message));
    }

    public static StreamEvent mode(String mode) {
        return new StreamEvent("mode", Map.of("mode", mode));
    }

    public static StreamEvent step(int idx, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idx", idx);
        m.put("name", name);
        return new StreamEvent("step", m);
    }

    public static StreamEvent toolCall(String tool, Map<String, ?> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", tool);
        m.put("params", params);
        return new StreamEvent("tool_call", m);
    }

    public static StreamEvent observation(String tool, String result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", tool);
        m.put("result", result);
        return new StreamEvent("observation", m);
    }

    public static StreamEvent ragResult(List<?> chunks) {
        return new StreamEvent("rag_result", Map.of("chunks", chunks == null ? List.of() : chunks));
    }

    public static StreamEvent token(String chunk) {
        return new StreamEvent("token", Map.of("chunk", chunk == null ? "" : chunk));
    }

    public static StreamEvent done(Object response) {
        return new StreamEvent("done", response);
    }

    public static StreamEvent error(String message) {
        return new StreamEvent("error", Map.of("message", message == null ? "" : message));
    }


    /** 图就绪：data = {levels: [[id,...], ...], nodes: {...}} */
    public static StreamEvent graphReady(List<List<String>> levels, Map<String, ?> nodes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("levels", levels == null ? List.of() : levels);
        m.put("nodes", nodes == null ? Map.of() : nodes);
        return new StreamEvent("graph_ready", m);
    }

    /** 节点开始执行：data = {id, tool} */
    public static StreamEvent nodeStart(String id, String tool) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id == null ? "" : id);
        m.put("tool", tool == null ? "" : tool);
        return new StreamEvent("node_start", m);
    }

    /** 节点完成：data = {id, tool, status} */
    public static StreamEvent nodeDone(String id, String tool, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id == null ? "" : id);
        m.put("tool", tool == null ? "" : tool);
        m.put("status", status == null ? "" : status);
        return new StreamEvent("node_done", m);
    }

    /** 竞速胜出：data = {race_group, winner, tool} */
    public static StreamEvent raceWon(String raceGroup, String winner, String tool) {
        return raceWon(raceGroup, winner, tool, 0.0, "first_success");
    }

    /** 竞速胜出，附带质量分和选择原因，便于前端展示与运行指标统计。 */
    public static StreamEvent raceWon(String raceGroup, String winner, String tool,
                                      double qualityScore, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("race_group", raceGroup == null ? "" : raceGroup);
        m.put("winner", winner == null ? "" : winner);
        m.put("tool", tool == null ? "" : tool);
        m.put("quality_score", qualityScore);
        m.put("reason", reason == null ? "" : reason);
        return new StreamEvent("race_won", m);
    }
}
