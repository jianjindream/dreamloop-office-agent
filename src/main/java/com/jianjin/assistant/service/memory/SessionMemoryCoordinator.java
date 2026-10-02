package com.jianjin.assistant.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.model.ConversationMessage;
import com.jianjin.assistant.service.llm.LlmService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Keeps raw session context bounded and turns evicted complete chat turns into a durable digest. */
@Component
public class SessionMemoryCoordinator {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AppConfig cfg;
    private final LlmService llm;
    private final InfrastructureService infra;

    public SessionMemoryCoordinator(AppConfig cfg, LlmService llm, InfrastructureService infra) {
        this.cfg = cfg; this.llm = llm; this.infra = infra;
    }

    public void compactBeforeRequest(MemoryScope scope, SessionMemoryState state, String incomingQuery) {
        int inputLimit = cfg.getMemory().getContextWindowTokens() - cfg.getMemory().getReservedOutputTokens();
        if (TokenEstimator.estimate(incomingQuery) > inputLimit) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "当前消息超过上下文 Token 预算");
        }
        if (!needsCompaction(state.summary(), state.messages().getMessages(), incomingQuery,
                cfg.getMemory().getShortTermMaxTurns(), cfg.getMemory().getHistoryMaxTokens())) return;
        CompactionPlan plan = plan(state.summary(), state.messages().getMessages(), incomingQuery,
                cfg.getMemory().getMinRecentTurns(), cfg.getMemory().getHistoryMaxTokens() * 3 / 5);
        if (!plan.evicted().isEmpty()) apply(scope, state, plan, summarize(plan.baseSummary(), plan.evicted()));
    }

    /** Starts a non-blocking batch compaction after a response once the soft threshold is reached. */
    public void scheduleSoftCompaction(MemoryScope scope, SessionMemoryState state) {
        if (!needsCompaction(state.summary(), state.messages().getMessages(), null,
                cfg.getMemory().getSummarySoftTurns(), cfg.getMemory().getSummarySoftTokens())) return;
        if (!state.beginAsyncSummary()) return;
        CompactionPlan plan = plan(state.summary(), state.messages().getMessages(), null,
                cfg.getMemory().getMinRecentTurns(), cfg.getMemory().getHistoryMaxTokens() * 3 / 5);
        if (plan.evicted().isEmpty()) { state.endAsyncSummary(); return; }
        new Thread(() -> {
            try {
                // LLM work deliberately happens outside the session lock so the next user request is not blocked.
                SessionSummary merged = summarize(plan.baseSummary(), plan.evicted());
                state.lock().lock();
                try {
                    if (matchesPrefix(state, plan)) apply(scope, state, plan, merged);
                } finally {
                    state.lock().unlock();
                }
            } finally {
                state.endAsyncSummary();
            }
        }, "session-summary").start();
    }

    public String renderSummary(SessionSummary summary) {
        if (summary == null || summary.isEmpty()) return "";
        StringBuilder out = new StringBuilder("【会话摘要】");
        add(out, "目标", summary.goal);
        add(out, "约束", summary.constraints);
        add(out, "决策", summary.decisions);
        add(out, "已完成", summary.completed);
        add(out, "待办", summary.pending);
        if (!summary.entities.isEmpty()) add(out, "实体", summary.entities.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList());
        add(out, "待确认问题", summary.openQuestions);
        add(out, "上下文备注", summary.contextNotes);
        return out.toString();
    }

    private boolean needsCompaction(SessionSummary summary, List<ConversationMessage> messages, String incomingQuery,
                                    int turnLimit, int tokenLimit) {
        if (countTurns(messages) + (incomingQuery == null ? 0 : 1) > turnLimit) return true;
        int tokens = TokenEstimator.estimate(renderSummary(summary));
        if (incomingQuery != null) tokens += TokenEstimator.estimateMessage("user", incomingQuery);
        for (ConversationMessage message : messages) tokens += TokenEstimator.estimateMessage(message.getRole(), message.getContent());
        return tokens > tokenLimit;
    }

    private CompactionPlan plan(SessionSummary summary, List<ConversationMessage> messages, String incomingQuery,
                                int targetTurns, int targetTokens) {
        List<ConversationMessage> all = new ArrayList<>(messages);
        List<ConversationMessage> evicted = new ArrayList<>();
        int keepMessages = Math.max(0, cfg.getMemory().getMinRecentTurns() * 2);
        while ((countTurns(all) > targetTurns || historyTokens(summary, all, incomingQuery) > targetTokens)
                && all.size() > keepMessages) {
            int take = completeTurnSize(all);
            if (take == 0 || all.size() - take < keepMessages) break;
            evicted.addAll(all.subList(0, take));
            all = new ArrayList<>(all.subList(take, all.size()));
        }
        return new CompactionPlan(copy(summary), evicted);
    }

    private int historyTokens(SessionSummary summary, List<ConversationMessage> messages, String incomingQuery) {
        int tokens = TokenEstimator.estimate(renderSummary(summary));
        if (incomingQuery != null) tokens += TokenEstimator.estimateMessage("user", incomingQuery);
        for (ConversationMessage message : messages) tokens += TokenEstimator.estimateMessage(message.getRole(), message.getContent());
        return tokens;
    }

    private void apply(MemoryScope scope, SessionMemoryState state, CompactionPlan plan, SessionSummary merged) {
        long throughId = plan.evicted().get(plan.evicted().size() - 1).getId();
        if (throughId > 0) merged.summarizedThroughId = throughId;
        trimSummary(merged, cfg.getMemory().getSummaryMaxTokens());
        // Keep raw rows as immutable product history. The cursor prevents summarized rows from being
        // loaded into the prompt again while the session/message API can still return the full transcript.
        infra.saveSessionSummary(scope.userId(), scope.sessionId(), merged);
        state.messages().removeOldest(plan.evicted().size());
        state.setSummary(merged);
    }

    private static boolean matchesPrefix(SessionMemoryState state, CompactionPlan plan) {
        List<ConversationMessage> current = state.messages().getMessages();
        if (current.size() < plan.evicted().size()) return false;
        if (state.summary().summarizedThroughId != plan.baseSummary().summarizedThroughId) return false;
        for (int i = 0; i < plan.evicted().size(); i++) {
            ConversationMessage now = current.get(i), before = plan.evicted().get(i);
            if (now.getId() != before.getId() || !now.getContent().equals(before.getContent())) return false;
        }
        return true;
    }

    private record CompactionPlan(SessionSummary baseSummary, List<ConversationMessage> evicted) {}

    private SessionSummary summarize(SessionSummary old, List<ConversationMessage> evicted) {
        if (!cfg.isRealLLM()) return fallback(old, evicted);
        String prompt = "将旧会话摘要与淘汰的对话合并成严格 JSON。只保留对话明确出现的事实，不推断；硬性约束不得改写。" +
                "JSON 字段必须是 goal(string), constraints(array), decisions(array), completed(array), pending(array), entities(object), openQuestions(array), contextNotes(array)。\n" +
                "旧摘要：" + safeJson(old) + "\n淘汰对话：\n" + transcript(evicted);
        try {
            String raw = llm.chat("", List.of(Map.of("role", "user", "content", prompt)));
            raw = raw == null ? "" : raw.trim().replace("```json", "").replace("```", "").trim();
            SessionSummary parsed = MAPPER.readValue(raw, SessionSummary.class);
            normalize(parsed);
            return parsed;
        } catch (Exception ignored) {
            return fallback(old, evicted);
        }
    }

    private static SessionSummary fallback(SessionSummary old, List<ConversationMessage> evicted) {
        SessionSummary result = copy(old);
        String note = transcript(evicted);
        if (!note.isBlank()) result.contextNotes.add(note);
        normalize(result);
        return result;
    }

    private static SessionSummary copy(SessionSummary source) {
        try { return MAPPER.readValue(MAPPER.writeValueAsString(source), SessionSummary.class); }
        catch (Exception e) { return new SessionSummary(); }
    }
    private static String safeJson(SessionSummary summary) {
        try { return MAPPER.writeValueAsString(summary); } catch (Exception e) { return "{}"; }
    }
    private static String transcript(List<ConversationMessage> messages) {
        StringBuilder out = new StringBuilder();
        for (ConversationMessage m : messages) out.append('[').append(m.getRole()).append("] ").append(m.getContent()).append('\n');
        return out.toString();
    }
    private static int completeTurnSize(List<ConversationMessage> messages) {
        if (messages.isEmpty()) return 0;
        return messages.size() > 1 && "user".equals(messages.get(0).getRole())
                && "assistant".equals(messages.get(1).getRole()) ? 2 : 1;
    }
    private static int countTurns(List<ConversationMessage> messages) {
        int result = 0; for (ConversationMessage m : messages) if ("user".equals(m.getRole())) result++; return result;
    }
    private static void add(StringBuilder out, String label, String value) { if (value != null && !value.isBlank()) out.append("\n").append(label).append(": ").append(value); }
    private static void add(StringBuilder out, String label, List<String> values) { if (values != null && !values.isEmpty()) out.append("\n").append(label).append(": ").append(String.join("；", values)); }

    private static void normalize(SessionSummary s) {
        if (s.goal == null) s.goal = "";
        s.constraints = unique(s.constraints); s.decisions = unique(s.decisions); s.completed = unique(s.completed);
        s.pending = unique(s.pending); s.openQuestions = unique(s.openQuestions); s.contextNotes = unique(s.contextNotes);
        if (s.entities == null) s.entities = new java.util.LinkedHashMap<>();
    }
    private static List<String> unique(List<String> values) {
        if (values == null) return new ArrayList<>();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String value : values) if (value != null && !value.isBlank()) set.add(value.trim());
        return new ArrayList<>(set);
    }
    private void trimSummary(SessionSummary s, int budget) {
        while (TokenEstimator.estimate(renderSummary(s)) > budget && !s.contextNotes.isEmpty()) s.contextNotes.remove(s.contextNotes.size() - 1);
        while (TokenEstimator.estimate(renderSummary(s)) > budget && !s.openQuestions.isEmpty()) s.openQuestions.remove(s.openQuestions.size() - 1);
        while (TokenEstimator.estimate(renderSummary(s)) > budget && !s.completed.isEmpty()) s.completed.remove(s.completed.size() - 1);
        while (TokenEstimator.estimate(renderSummary(s)) > budget && !s.pending.isEmpty()) s.pending.remove(s.pending.size() - 1);
    }
}
