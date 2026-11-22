package com.jianjin.assistant.application.chat;

import com.jianjin.assistant.application.chat.subagent.SubAgent;
import com.jianjin.assistant.application.chat.subagent.SubAgentRegistry;
import com.jianjin.assistant.application.chat.subagent.SubAgentTask;
import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.domain.graph.Node;
import com.jianjin.assistant.domain.graph.NodeStatus;
import com.jianjin.assistant.domain.graph.NodeType;
import com.jianjin.assistant.domain.graph.TaskGraph;
import com.jianjin.assistant.model.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class GraphRuntime {

    private static final Logger log = LoggerFactory.getLogger(GraphRuntime.class);

    public static class Config {
        public final int maxParallel;
        public final int raceTimeoutMs;
        public final int raceGraceMs;
        public final int raceMinResultChars;
        public final boolean enableRacing;
        public Config(int maxParallel, int raceTimeoutMs, boolean enableRacing) {
            this(maxParallel, raceTimeoutMs, 150, 12, enableRacing);
        }
        public Config(int maxParallel, int raceTimeoutMs, int raceGraceMs,
                      int raceMinResultChars, boolean enableRacing) {
            this.maxParallel = maxParallel <= 0 ? 2 : maxParallel;
            this.raceTimeoutMs = raceTimeoutMs <= 0 ? 30000 : raceTimeoutMs;
            this.raceGraceMs = Math.max(0, raceGraceMs);
            this.raceMinResultChars = Math.max(1, raceMinResultChars);
            this.enableRacing = enableRacing;
        }
        public static Config defaults() { return new Config(2, 30000, 150, 12, true); }
        public static Config from(AppConfig cfg) {
            AppConfig.GraphConfig g = cfg.getGraph();
            if (g == null) return defaults();
            return new Config(g.getMaxParallel(), g.getRaceTimeoutMs(), g.getRaceGraceMs(),
                    g.getRaceMinResultChars(), g.isEnableRacing());
        }
    }

    /** 单节点结果 */
    public static class NodeResult {
        public final NodeStatus status;
        public final String result;
        public final String error;
        public NodeResult(NodeStatus status, String result, String error) {
            this.status = status; this.result = result; this.error = error;
        }
    }

    /** 整图执行结果 */
    public static class GraphResult {
        public List<String> observations = new ArrayList<>();
        public Map<String, NodeResult> nodeResults = new LinkedHashMap<>();
        public GraphOutcome outcome = GraphOutcome.SUCCESS;
        public String failureReason = "";
        public boolean interrupted;
        public String interruptedAt = "";
        public String interruptedMsg = "";
    }

    public enum GraphOutcome {
        SUCCESS,
        PARTIAL_SUCCESS,
        FAILED,
        INTERRUPTED
    }

    private final TaskGraph graph;
    private final AppConfig appCfg;
    private final Config cfg;
    private final Map<String, Tool> tools;
    private final SubAgentRegistry subAgents;
    private final String taskQuery;
    private final String ownerKey;
    private final String sessionId;
    private final String requestId;
    private final Semaphore sem;
    private final Object mu = new Object();
    private final Map<String, String> results = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();
    private final Consumer<StreamEvent> onEvent;
    private final AtomicBoolean cancelled;
    /** 框架内部的关键路径终止；不能与用户主动取消混为一谈。 */
    private final AtomicBoolean fatalAborted = new AtomicBoolean(false);
    private volatile String fatalReason = "";
    private final Set<Future<?>> activeInvocations = ConcurrentHashMap.newKeySet();
    private ExecutorService invocationExecutor;

    public GraphRuntime(TaskGraph graph, AppConfig appCfg, Config cfg,
                        Map<String, Tool> tools, AtomicBoolean cancelled,
                        Consumer<StreamEvent> onEvent) {
        this(graph, appCfg, cfg, tools, null, "", "default:default", cancelled, onEvent);
    }

    public GraphRuntime(TaskGraph graph, AppConfig appCfg, Config cfg,
                        Map<String, Tool> tools, SubAgentRegistry subAgents,
                        String taskQuery, AtomicBoolean cancelled,
                        Consumer<StreamEvent> onEvent) {
        this(graph, appCfg, cfg, tools, subAgents, taskQuery,
                "default:default", cancelled, onEvent);
    }

    public GraphRuntime(TaskGraph graph, AppConfig appCfg, Config cfg,
                        Map<String, Tool> tools, SubAgentRegistry subAgents,
                        String taskQuery, String ownerKey, AtomicBoolean cancelled,
                        Consumer<StreamEvent> onEvent) {
        this(graph, appCfg, cfg, tools, subAgents, taskQuery, ownerKey,
                "default", "", cancelled, onEvent);
    }

    public GraphRuntime(TaskGraph graph, AppConfig appCfg, Config cfg,
                        Map<String, Tool> tools, SubAgentRegistry subAgents,
                        String taskQuery, String ownerKey, String sessionId, String requestId,
                        AtomicBoolean cancelled, Consumer<StreamEvent> onEvent) {
        this.graph = graph;
        this.appCfg = appCfg;
        this.cfg = cfg == null ? Config.from(appCfg) : cfg;
        this.tools = tools;
        this.subAgents = subAgents;
        this.taskQuery = taskQuery == null ? "" : taskQuery;
        this.ownerKey = ownerKey == null || ownerKey.isBlank() ? "default:default" : ownerKey;
        this.sessionId = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        this.requestId = requestId == null ? "" : requestId;
        this.sem = new Semaphore(this.cfg.maxParallel);
        this.cancelled = cancelled == null ? new AtomicBoolean(false) : cancelled;
        this.onEvent = onEvent == null ? e -> {} : onEvent;
    }

    /** 执行整张图：节点完成后立即解锁其下游，不设置拓扑层全局栅栏。 */
    public GraphResult execute() {
        List<List<String>> levels;
        try {
            graph.validate();
            levels = graph.topologicalLevels();
        } catch (Exception e) {
            log.warn("GraphRuntime: 图校验失败 {}", e.getMessage());
            GraphResult r = new GraphResult();
            r.outcome = GraphOutcome.FAILED;
            r.failureReason = "图校验失败: " + e.getMessage();
            return r;
        }

        // 转为字符串状态供前端订阅
        Map<String, Object> nodeView = new LinkedHashMap<>();
        for (Map.Entry<String, Node> e : graph.getNodes().entrySet()) {
            Node n = e.getValue();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", n.getId()); v.put("tool", n.getToolName());
            v.put("name", n.getName()); v.put("depends_on", n.getDependsOn());
            v.put("race_group", n.getRaceGroup()); v.put("status", n.getStatus().value());
            v.put("dependency_mode", n.getDependencyMode().name());
            v.put("quorum", n.getQuorum());
            v.put("required_output", n.isRequiredOutput());
            v.put("failure_policy", n.getFailurePolicy().name());
            nodeView.put(e.getKey(), v);
        }
        safeEmit(StreamEvent.graphReady(levels, nodeView));

        Map<String, ExecutionUnit> units = buildExecutionUnits();

        ExecutorService scheduler = Executors.newVirtualThreadPerTaskExecutor();
        invocationExecutor = Executors.newVirtualThreadPerTaskExecutor();
        CompletionService<String> completions = new ExecutorCompletionService<>(scheduler);
        Map<Future<String>, String> scheduled = new LinkedHashMap<>();
        Set<String> submitted = new HashSet<>();
        Set<String> settled = new HashSet<>();
        try {
            advanceUnits(units, completions, scheduled, submitted, settled);
            while (settled.size() < units.size()) {
                if (cancelled.get()) {
                    return interrupted("图执行被用户中断");
                }
                if (fatalAborted.get()) {
                    return failedResult(fatalReason);
                }
                Future<String> finished;
                try {
                    finished = completions.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return interrupted("等待节点完成时线程被中断");
                }
                if (finished == null) continue;
                String unitId;
                try {
                    unitId = finished.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return interrupted("读取节点结果时线程被中断");
                } catch (ExecutionException e) {
                    unitId = scheduled.get(finished);
                    failUnit(units.get(unitId), rootMessage(e));
                }
                if (unitId != null) settled.add(unitId);
                advanceUnits(units, completions, scheduled, submitted, settled);
                String criticalFailure = criticalFailureReason();
                if (!criticalFailure.isEmpty()) {
                    abortForCriticalFailure(criticalFailure);
                    return failedResult(criticalFailure);
                }
            }
        } finally {
            if (stopRequested()) scheduled.keySet().forEach(future -> future.cancel(true));
            scheduler.shutdownNow();
            cancelActiveInvocations();
            invocationExecutor.shutdownNow();
        }
        return buildResult();
    }

    private static class ExecutionUnit {
        final String id;
        final String raceGroup;
        final List<String> nodeIds = new ArrayList<>();
        final Set<String> dependencies = new LinkedHashSet<>();

        ExecutionUnit(String id, String raceGroup) {
            this.id = id;
            this.raceGroup = raceGroup;
        }
    }

    private Map<String, ExecutionUnit> buildExecutionUnits() {
        Map<String, List<String>> raceGroups = graph.raceGroups();
        Set<String> eligibleRaceGroups = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : raceGroups.entrySet()) {
            boolean eligible = entry.getValue().size() > 1 && entry.getValue().stream()
                    .map(graph.getNodes()::get)
                    .allMatch(this::isRaceEligible);
            if (eligible) {
                eligibleRaceGroups.add(entry.getKey());
            } else if (entry.getValue().size() > 1) {
                log.warn("GraphRuntime: 竞速组 {} 含不可安全竞速的执行体，降级为普通并行", entry.getKey());
            }
        }
        Map<String, ExecutionUnit> units = new LinkedHashMap<>();
        Map<String, String> nodeToUnit = new HashMap<>();
        for (Node node : graph.getNodes().values()) {
            boolean racing = cfg.enableRacing && !node.getRaceGroup().isEmpty()
                    && eligibleRaceGroups.contains(node.getRaceGroup());
            String unitId = racing ? "race:" + node.getRaceGroup() : "node:" + node.getId();
            ExecutionUnit unit = units.computeIfAbsent(unitId,
                    ignored -> new ExecutionUnit(unitId, racing ? node.getRaceGroup() : ""));
            unit.nodeIds.add(node.getId());
            nodeToUnit.put(node.getId(), unitId);
        }
        for (ExecutionUnit unit : units.values()) {
            for (String nodeId : unit.nodeIds) {
                for (String dependencyNode : graph.getNodes().get(nodeId).getDependsOn()) {
                    String dependencyUnit = nodeToUnit.get(dependencyNode);
                    if (dependencyUnit != null && !dependencyUnit.equals(unit.id)) {
                        unit.dependencies.add(dependencyUnit);
                    }
                }
            }
        }
        return units;
    }

    private boolean isRaceEligible(Node node) {
        if (node == null || node.getType() != NodeType.TOOL || tools == null) return false;
        Tool tool = tools.get(node.getToolName());
        return tool != null && tool.isRaceSafe();
    }

    private void submitUnit(ExecutionUnit unit, CompletionService<String> completions,
                            Map<Future<String>, String> scheduled, Set<String> submitted) {
        if (unit == null || !submitted.add(unit.id)) return;
        Future<String> future = completions.submit(() -> {
            runUnit(unit);
            return unit.id;
        });
        scheduled.put(future, unit.id);
    }

    private void runUnit(ExecutionUnit unit) {
        if (!unit.raceGroup.isEmpty() && unit.nodeIds.size() > 1) {
            runRace(new RaceGroup(unit.raceGroup, unit.nodeIds));
        } else {
            runSingle(unit.nodeIds.getFirst());
        }
    }

    private void failUnit(ExecutionUnit unit, String message) {
        if (unit == null) return;
        for (String nodeId : unit.nodeIds) {
            applyAttempt(RaceAttempt.failed(nodeId, message));
        }
    }

    private enum DependencyDecision { WAIT, READY, IMPOSSIBLE }

    /**
     * 扫描尚未提交的执行单元：满足条件的立即提交，已经不可能满足的立即跳过。
     * 重复扫描可以在一次失败后把整条不可达后继链立刻结算，无需等待其他慢依赖。
     */
    private void advanceUnits(Map<String, ExecutionUnit> units,
                              CompletionService<String> completions,
                              Map<Future<String>, String> scheduled,
                              Set<String> submitted, Set<String> settled) {
        boolean changed;
        do {
            changed = false;
            for (ExecutionUnit unit : units.values()) {
                if (submitted.contains(unit.id) || settled.contains(unit.id)) continue;
                DependencyDecision decision = dependencyDecision(unit);
                if (decision == DependencyDecision.READY) {
                    submitUnit(unit, completions, scheduled, submitted);
                    changed = true;
                } else if (decision == DependencyDecision.IMPOSSIBLE) {
                    unit.nodeIds.forEach(this::skipForFailedDependencies);
                    settled.add(unit.id);
                    changed = true;
                }
            }
        } while (changed);
    }

    private DependencyDecision dependencyDecision(ExecutionUnit unit) {
        DependencyDecision result = DependencyDecision.READY;
        for (String nodeId : unit.nodeIds) {
            Node node = graph.getNodes().get(nodeId);
            DependencyDecision current = dependencyDecision(node);
            if (current == DependencyDecision.IMPOSSIBLE) return current;
            if (current == DependencyDecision.WAIT) result = current;
        }
        return result;
    }

    private DependencyDecision dependencyDecision(Node node) {
        if (node == null || node.getDependsOn() == null || node.getDependsOn().isEmpty()) {
            return DependencyDecision.READY;
        }
        int success = 0;
        int terminal = 0;
        for (String depId : node.getDependsOn()) {
            Node dep = graph.getNodes().get(depId);
            if (successfulDependency(dep) != null) success++;
            if (isTerminal(dep)) terminal++;
        }
        int total = node.getDependsOn().size();
        int possible = success + (total - terminal);
        return switch (node.getDependencyMode()) {
            case ALL -> success == total ? DependencyDecision.READY
                    : possible < total ? DependencyDecision.IMPOSSIBLE : DependencyDecision.WAIT;
            case ANY -> success > 0 ? DependencyDecision.READY
                    : terminal == total ? DependencyDecision.IMPOSSIBLE : DependencyDecision.WAIT;
            case QUORUM -> {
                int needed = Math.min(Math.max(1, node.getQuorum()), total);
                yield success >= needed ? DependencyDecision.READY
                        : possible < needed ? DependencyDecision.IMPOSSIBLE : DependencyDecision.WAIT;
            }
            case OPTIONAL -> terminal == total ? DependencyDecision.READY : DependencyDecision.WAIT;
        };
    }

    private static boolean isTerminal(Node node) {
        if (node == null) return true;
        return switch (node.getStatus()) {
            case DONE, FAILED, SKIPPED, CANCELLED -> true;
            case PENDING, RUNNING -> false;
        };
    }

    private String criticalFailureReason() {
        for (Node node : graph.getNodes().values()) {
            if (node.getStatus() == NodeStatus.FAILED
                    && node.getFailurePolicy() == com.jianjin.assistant.domain.graph.FailurePolicy.FAIL_FAST) {
                return "关键节点 " + node.getId() + "(" + node.executorName() + ") 失败: " + node.getError();
            }
        }
        for (Node node : graph.getNodes().values()) {
            if (!node.isRequiredOutput()) continue;
            if (successfulDependency(node) != null) continue;
            if (raceGroupStillViable(node)) continue;
            if (isTerminal(node)) {
                return "必需输出 " + node.getId() + "(" + node.executorName() + ") 已不可达"
                        + (node.getError().isEmpty() ? "" : ": " + node.getError());
            }
        }
        return "";
    }

    private boolean raceGroupStillViable(Node node) {
        if (node.getRaceGroup() == null || node.getRaceGroup().isEmpty()) return false;
        for (Node candidate : graph.getNodes().values()) {
            if (node.getRaceGroup().equals(candidate.getRaceGroup()) && !isTerminal(candidate)) return true;
        }
        return false;
    }

    private void abortForCriticalFailure(String reason) {
        fatalReason = reason == null ? "关键路径失败" : reason;
        fatalAborted.set(true);
        for (Node node : graph.getNodes().values()) {
            if (node.getStatus() == NodeStatus.PENDING || node.getStatus() == NodeStatus.RUNNING) {
                graph.setNodeStatus(node.getId(), NodeStatus.CANCELLED);
                graph.setNodeError(node.getId(), "关键路径失败，已停止: " + fatalReason);
                safeEmit(StreamEvent.nodeDone(node.getId(), node.executorName(),
                        NodeStatus.CANCELLED.value(), node.getError()));
            }
        }
        cancelActiveInvocations();
    }

    private boolean stopRequested() {
        return cancelled.get() || fatalAborted.get();
    }

    private String stopReason() {
        return cancelled.get() ? "被用户中断" : "关键路径失败，图执行已停止";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    // ─────────────── 竞速执行（First-qualified / bounded-best） ───────────────

    private static class RaceGroup {
        final String raceGroup;
        final List<String> nodeIds;
        RaceGroup(String rg, List<String> ids) { this.raceGroup = rg; this.nodeIds = ids; }
    }

    private void runRace(RaceGroup g) {
        AtomicBoolean raceClosed = new AtomicBoolean(false);
        BlockingQueue<RaceAttempt> ch = new ArrayBlockingQueue<>(g.nodeIds.size());
        Map<String, Thread> threads = new HashMap<>();

        for (String id : g.nodeIds) {
            Thread t = Thread.ofVirtual().name("race-" + g.raceGroup + "-" + id).unstarted(() -> {
                if (raceClosed.get() || stopRequested()) {
                    ch.offer(RaceAttempt.skipped(id, "竞速取消"));
                    return;
                }
                boolean acquired = acquire();
                if (!acquired) {
                    ch.offer(RaceAttempt.skipped(id, "竞速等待被中断"));
                    return;
                }
                try {
                    if (raceClosed.get() || stopRequested()) {
                        ch.offer(RaceAttempt.skipped(id, "竞速取消"));
                        return;
                    }
                    ch.offer(executeAttempt(id, raceClosed));
                } catch (Throwable ex) {
                    ch.offer(RaceAttempt.failed(id,
                            ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                } finally {
                    sem.release();
                }
            });
            threads.put(id, t);
            // 必须用 Thread.start()，不能走 pool.execute(t) ——
            // 后者会让 pool 的 worker 调 t.run()，t 自己的线程从未启动，
            // 后续对 t.interrupt() 无效，竞速失败者将无法被打断。
            t.start();
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cfg.raceTimeoutMs);
        Map<String, RaceAttempt> attempts = new LinkedHashMap<>();
        Map<String, RaceEvaluation> evaluations = new LinkedHashMap<>();
        RaceAttempt qualifiedWinner = null;
        RaceAttempt bestEffort = null;
        long qualifiedDeadline = Long.MAX_VALUE;
        int collected = 0;
        try {
            while (collected < g.nodeIds.size()) {
                if (stopRequested()) break;
                long now = System.nanoTime();
                long effectiveDeadline = Math.min(deadline, qualifiedDeadline);
                long left = effectiveDeadline - now;
                if (left <= 0) break;
                RaceAttempt r = ch.poll(Math.min(left, TimeUnit.MILLISECONDS.toNanos(100)),
                        TimeUnit.NANOSECONDS);
                if (r == null) continue;
                collected++;
                attempts.put(r.id, r);
                if (r.status == NodeStatus.DONE) {
                    RaceEvaluation evaluation = evaluateRaceResult(r);
                    evaluations.put(r.id, evaluation);
                    if (evaluation.usable && betterRaceCandidate(r, bestEffort, evaluations)) {
                        bestEffort = r;
                    }
                    if (evaluation.qualified) {
                        if (qualifiedWinner == null) {
                            qualifiedDeadline = Math.min(deadline, System.nanoTime()
                                    + TimeUnit.MILLISECONDS.toNanos(cfg.raceGraceMs));
                        }
                        if (betterRaceCandidate(r, qualifiedWinner, evaluations)) {
                            qualifiedWinner = r;
                        }
                        if (cfg.raceGraceMs == 0) break;
                    }
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            // 无论胜出、全部失败还是超时，都关闭竞速，避免迟到线程回写节点状态。
            raceClosed.set(true);
            RaceAttempt selected = qualifiedWinner != null ? qualifiedWinner : bestEffort;
            interruptRaceLosers(threads, selected == null ? null : selected.id);
            joinRaceThreads(threads, 200);
        }

        // 首选通过质量门控的结果；没有合格结果时，允许非空弱结果作为 best-effort 兜底。
        RaceAttempt winner = qualifiedWinner != null ? qualifiedWinner : bestEffort;

        // 结算：未胜出节点 → 标 SKIPPED；都失败 → FAILED
        if (winner != null) {
            Node winnerNode = graph.getNodes().get(winner.id);
            RaceEvaluation winnerEvaluation = evaluations.get(winner.id);
            applyAttempt(winner);
            // 整图可能在竞速结算的同一时刻被关键失败终止；不得发送迟到的 DONE。
            if (winnerNode.getStatus() != NodeStatus.DONE) return;
            safeEmit(StreamEvent.nodeDone(winner.id, winnerNode.executorName(), NodeStatus.DONE.value()));
            safeEmit(StreamEvent.raceWon(g.raceGroup, winner.id, winnerNode.executorName(),
                    winnerEvaluation == null ? 0.0 : winnerEvaluation.score,
                    winnerEvaluation == null ? "first_success" : winnerEvaluation.reason));
            safeEmit(StreamEvent.observation(winnerNode.executorName(), winner.result));
            for (String id : g.nodeIds) {
                Node n = graph.getNodes().get(id);
                if (!id.equals(winner.id)) {
                    synchronized (mu) {
                        if (n.getStatus() == NodeStatus.CANCELLED) continue;
                        graph.setNodeStatus(id, NodeStatus.SKIPPED);
                        graph.setNodeResult(id, "");
                        graph.setNodeError(id, "");
                    }
                    safeEmit(StreamEvent.nodeDone(id, n.executorName(), NodeStatus.SKIPPED.value()));
                }
            }
        } else {
            for (String id : g.nodeIds) {
                RaceAttempt attempt = attempts.get(id);
                if (stopRequested()) {
                    attempt = RaceAttempt.cancelled(id, stopReason());
                } else if (attempt == null || attempt.status == NodeStatus.SKIPPED) {
                    attempt = RaceAttempt.failed(id, "竞速超时（" + cfg.raceTimeoutMs + "ms）");
                } else if (attempt.status == NodeStatus.DONE) {
                    RaceEvaluation evaluation = evaluations.get(id);
                    attempt = RaceAttempt.failed(id, "竞速结果未通过质量门控: "
                            + (evaluation == null ? "unusable_result" : evaluation.reason));
                }
                applyAttempt(attempt);
                Node n = graph.getNodes().get(id);
                safeEmit(StreamEvent.nodeDone(id, n.executorName(), n.getStatus().value(), n.getError()));
                if (n.getStatus() == NodeStatus.FAILED) {
                    safeEmit(StreamEvent.observation(n.executorName(), "执行失败: " + n.getError()));
                }
            }
        }
    }

    private static class RaceAttempt {
        final String id;
        final NodeStatus status;
        final String result;
        final String err;
        RaceAttempt(String id, NodeStatus status, String result, String err) {
            this.id = id;
            this.status = status;
            this.result = result == null ? "" : result;
            this.err = err == null ? "" : err;
        }
        static RaceAttempt done(String id, String result) {
            return new RaceAttempt(id, NodeStatus.DONE, result, "");
        }
        static RaceAttempt failed(String id, String err) {
            return new RaceAttempt(id, NodeStatus.FAILED, "", err);
        }
        static RaceAttempt cancelled(String id, String err) {
            return new RaceAttempt(id, NodeStatus.CANCELLED, "", err);
        }
        static RaceAttempt skipped(String id, String err) {
            return new RaceAttempt(id, NodeStatus.SKIPPED, "", err);
        }
    }

    private static class RaceEvaluation {
        final boolean usable;
        final boolean qualified;
        final double score;
        final String reason;

        RaceEvaluation(boolean usable, boolean qualified, double score, String reason) {
            this.usable = usable;
            this.qualified = qualified;
            this.score = score;
            this.reason = reason;
        }
    }

    /**
     * 检索竞速不能把“调用成功”等同于“结果可用”。当前工具接口仍返回字符串，
     * 因此先用保守的通用门控过滤空结果/明确的无结果文案，并用内容量和证据特征
     * 做轻量评分。后续若工具升级为结构化 RetrievalResult，可在这里替换评分器。
     */
    private RaceEvaluation evaluateRaceResult(RaceAttempt attempt) {
        String result = attempt == null || attempt.result == null ? "" : attempt.result.strip();
        if (result.isEmpty()) {
            return new RaceEvaluation(false, false, 0.0, "empty_result");
        }

        Node node = graph.getNodes().get(attempt.id);
        String executor = node == null ? "" : node.executorName().toLowerCase();
        boolean retrieval = executor.contains("search") || executor.contains("rag")
                || executor.contains("retriev");
        if (!retrieval) {
            return new RaceEvaluation(true, true, 1.0, "successful_result");
        }

        String lower = result.toLowerCase();
        for (String marker : List.of("未找到", "没有找到", "暂无相关", "无相关内容",
                "知识库为空", "no result", "not found", "无法搜索")) {
            if (lower.contains(marker)) {
                return new RaceEvaluation(false, false, 0.0, "explicit_no_result");
            }
        }

        int chars = result.codePointCount(0, result.length());
        double score = 0.35 + Math.min(0.45, chars / 600.0 * 0.45);
        if (lower.contains("http://") || lower.contains("https://")) score += 0.10;
        if (result.contains("\n-") || result.contains("\n1.") || result.contains("来源")) score += 0.10;
        score = Math.min(1.0, score);
        boolean qualified = chars >= cfg.raceMinResultChars;
        return new RaceEvaluation(true, qualified, score,
                qualified ? "qualified_result" : "best_effort_below_threshold");
    }

    private static boolean betterRaceCandidate(RaceAttempt candidate, RaceAttempt current,
                                               Map<String, RaceEvaluation> evaluations) {
        if (candidate == null) return false;
        if (current == null) return true;
        RaceEvaluation next = evaluations.get(candidate.id);
        RaceEvaluation previous = evaluations.get(current.id);
        if (next == null) return false;
        if (previous == null) return true;
        return next.score > previous.score;
    }

    // ─────────────── 单节点执行 ───────────────

    private void runSingle(String nodeId) {
        boolean acquired = acquire();
        if (!acquired) {
            applyAttempt(RaceAttempt.cancelled(nodeId, "等待执行许可时被中断"));
            return;
        }
        try {
            RaceAttempt attempt = executeAttempt(nodeId, null);
            applyAttempt(attempt);
            Node node = graph.getNodes().get(nodeId);
            safeEmit(StreamEvent.nodeDone(nodeId, node.executorName(), attempt.status.value(), attempt.err));
            if (attempt.status == NodeStatus.DONE) {
                safeEmit(StreamEvent.observation(node.executorName(), attempt.result));
            } else if (attempt.status == NodeStatus.FAILED) {
                safeEmit(StreamEvent.observation(node.executorName(), "执行失败: " + attempt.err));
            }
        } finally {
            sem.release();
        }
    }

    /**
     * 真正调用工具的核心：状态机、重试和尝试结果都在这里。
     * 竞速分支只返回尝试结果，由 runRace 唯一负责提交终态，避免出现多个 DONE。
     */
    private RaceAttempt executeAttempt(String nodeId, AtomicBoolean stopFlag) {
        Node node = graph.getNodes().get(nodeId);
        if (node == null) return RaceAttempt.failed(nodeId, "节点不存在");
        String executor = node.executorName();
        safeEmit(StreamEvent.nodeStart(nodeId, executor));

        // Thought + Action 步骤
        safeEmit(StreamEvent.step(idAsInt(nodeId), node.getName()));
        safeEmit(StreamEvent.toolCall(executor, node.getParams()));

        graph.setNodeStatus(nodeId, NodeStatus.RUNNING);

        // 工具节点：先校验工具存在；子 Agent 节点：先校验注册
        if (node.getType() == NodeType.SUB_AGENT) {
            if (subAgents == null || !subAgents.has(node.getAgentName())) {
                String msg = "子 Agent " + node.getAgentName() + " 不存在";
                return RaceAttempt.failed(nodeId, msg);
            }
        } else {
            if (tools.get(node.getToolName()) == null) {
                String msg = "工具 " + node.getToolName() + " 不在允许列表中";
                return RaceAttempt.failed(nodeId, msg);
            }
        }

        AppConfig.HarnessConfig h = appCfg.getHarness();
        int maxRetries = h == null ? 3 : h.getMaxRetries();
        int retryDelay = Math.max(0, h == null ? 200 : h.getRetryDelayMs());

        String result = null;
        String lastErr = null;
        boolean retryable = node.getType() == NodeType.TOOL
                && tools.get(node.getToolName()) != null
                && tools.get(node.getToolName()).isRetryable();
        int maxAttempts = retryable ? 1 + Math.max(0, maxRetries) : 1;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (stopRequested()) {
                return RaceAttempt.cancelled(nodeId, stopReason());
            }
            if (stopFlag != null && stopFlag.get()) {
                // 竞速：他人胜出 → 不再重试，直接返回让外层标 SKIPPED
                return RaceAttempt.skipped(nodeId, "竞速取消");
            }
            try {
                result = invokeWithTimeout(node);
                if (stopFlag != null && stopFlag.get()) {
                    return RaceAttempt.skipped(nodeId, "竞速取消");
                }
                lastErr = null;
                break;
            } catch (Exception e) {
                lastErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (stopRequested()) {
                    return RaceAttempt.cancelled(nodeId, stopReason());
                }
                if (stopFlag != null && stopFlag.get()) {
                    return RaceAttempt.skipped(nodeId, "竞速取消");
                }
                if (attempt < maxAttempts - 1) {
                    graph.setNodeRetryCount(nodeId, attempt + 1);
                    try { Thread.sleep(retryDelay); }
                    catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        // 竞速 / 取消引起的 interrupt → 立即返回
                        return stopFlag == null
                                ? RaceAttempt.cancelled(nodeId, "执行线程被中断")
                                : RaceAttempt.skipped(nodeId, "竞速取消");
                    }
                }
            }
        }

        if (lastErr != null) {
            return RaceAttempt.failed(nodeId, lastErr);
        }
        return RaceAttempt.done(nodeId, result);
    }

    // ─────────────── 辅助方法 ───────────────

    /** 按节点类型分派：sub-agent 节点跑注册表里的 SubAgent；其余走工具调用。 */
    private String invoke(Node node) throws Exception {
        if (node.getType() == NodeType.SUB_AGENT) {
            SubAgent sa = subAgents.get(node.getAgentName());
            Map<String, String> upstream = upstreamResults(node);
            SubAgentTask task = new SubAgentTask(node.getId(), node.getGoal(), taskQuery, ownerKey, upstream);
            return sa.run(task, cancelled);
        }
        Tool t = tools.get(node.getToolName());
        Map<String, Object> params = new HashMap<>();
        if (node.getParams() != null) {
            node.getParams().forEach((key, value) -> params.put(key, resolveParamValue(node, value)));
        }
        if (t.isExecutionContextAware()) {
            params.put(Tool.CONTEXT_OWNER_KEY, ownerKey);
            params.put(Tool.CONTEXT_SESSION_ID, sessionId);
            params.put(Tool.CONTEXT_REQUEST_ID, requestId);
        }
        return t.getExecute().apply(params);
    }

    /** 解析 Planner 在工具参数中声明的 {{n1.result}} / {{query}} 占位符。 */
    private String resolveParamValue(Node node, String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        String resolved = value.replace("{{query}}", taskQuery);
        if (node.getDependsOn() == null) return resolved;
        for (String depId : node.getDependsOn()) {
            Node dep = graph.getNodes().get(depId);
            Node source = successfulDependency(dep);
            String depResult = source == null || source.getResult() == null ? "" : source.getResult();
            resolved = resolved.replace("{{" + depId + ".result}}", depResult);
        }
        return resolved;
    }

    /**
     * 对单次工具/子 Agent 调用应用 step-timeout-ms。FutureTask 在线程被取消后不会再把
     * 迟到结果提交给图；底层执行体仍应正确响应 interrupt，才能同时停止其外部副作用。
     */
    private String invokeWithTimeout(Node node) throws Exception {
        AppConfig.HarnessConfig h = appCfg.getHarness();
        int timeoutMs = h == null ? 5000 : h.getStepTimeoutMs();
        if (timeoutMs <= 0) return invoke(node);

        Future<String> task = invocationExecutor.submit(() -> invoke(node));
        activeInvocations.add(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new RuntimeException("执行超时（" + timeoutMs + "ms）");
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } finally {
            activeInvocations.remove(task);
        }
    }

    /** 取所有 depends_on 节点的 result，key = "<nodeId>:<executor-name>"，便于子 Agent 识别。 */
    private Map<String, String> upstreamResults(Node node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node.getDependsOn() == null) return out;
        // 按 depId 排序，输出稳定（doc_agent 依赖该顺序定位 writer/review）
        java.util.Set<String> emitted = new java.util.HashSet<>();
        for (String depId : new TreeSet<>(node.getDependsOn())) {
            Node dep = graph.getNodes().get(depId);
            Node source = successfulDependency(dep);
            if (source == null || source.getResult() == null || source.getResult().isEmpty()
                    || !emitted.add(source.getId())) continue;
            String exec = source.executorName();
            String key = exec.isEmpty() ? source.getId() : source.getId() + ":" + exec;
            out.put(key, source.getResult());
        }
        return out;
    }

    /** 竞速落败节点由同组胜者代偿，因此仍可满足下游依赖。 */
    private Node successfulDependency(Node dep) {
        if (dep == null) return null;
        if (dep.getStatus() == NodeStatus.DONE) return dep;
        if (dep.getStatus() != NodeStatus.SKIPPED || dep.getRaceGroup() == null
                || dep.getRaceGroup().isEmpty()) return null;
        for (Node candidate : graph.getNodes().values()) {
            if (candidate.getStatus() == NodeStatus.DONE
                    && dep.getRaceGroup().equals(candidate.getRaceGroup())) {
                return candidate;
            }
        }
        return null;
    }

    private void skipForFailedDependencies(String nodeId) {
        Node node = graph.getNodes().get(nodeId);
        if (node == null) return;
        List<String> failed = new ArrayList<>();
        for (String depId : node.getDependsOn()) {
            Node dep = graph.getNodes().get(depId);
            if (dep == null) {
                failed.add(depId + "(missing)");
            } else if (successfulDependency(dep) == null) {
                failed.add(depId + "(" + dep.getStatus().value() + ")");
            }
        }
        String msg = "上游依赖未成功: " + String.join(", ", failed);
        synchronized (mu) {
            graph.setNodeStatus(nodeId, NodeStatus.SKIPPED);
            graph.setNodeError(nodeId, msg);
            errors.put(nodeId, msg);
        }
        safeEmit(StreamEvent.nodeDone(nodeId, node.executorName(), NodeStatus.SKIPPED.value(), msg));
        safeEmit(StreamEvent.observation(node.executorName(), msg));
    }

    /** 把一次执行尝试原子地提交到图，竞速线程本身不直接提交终态。 */
    private void applyAttempt(RaceAttempt attempt) {
        if (attempt == null) return;
        synchronized (mu) {
            Node node = graph.getNodes().get(attempt.id);
            if (node != null && node.getStatus() == NodeStatus.CANCELLED
                    && attempt.status != NodeStatus.CANCELLED) return;
            graph.setNodeStatus(attempt.id, attempt.status);
            if (attempt.status == NodeStatus.DONE) {
                graph.setNodeResult(attempt.id, attempt.result);
                graph.setNodeError(attempt.id, "");
                results.put(attempt.id, attempt.result);
                errors.remove(attempt.id);
            } else {
                graph.setNodeResult(attempt.id, "");
                graph.setNodeError(attempt.id, attempt.err);
                results.remove(attempt.id);
                if (!attempt.err.isEmpty()) errors.put(attempt.id, attempt.err);
            }
        }
    }

    private static void interruptRaceLosers(Map<String, Thread> threads, String winnerId) {
        for (Map.Entry<String, Thread> e : threads.entrySet()) {
            if (winnerId == null || !e.getKey().equals(winnerId)) e.getValue().interrupt();
        }
    }

    /** 短暂回收协作式响应中断的竞速线程；不让不响应中断的工具阻塞整张图。 */
    private static void joinRaceThreads(Map<String, Thread> threads, long maxWaitMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, maxWaitMs));
        for (Thread t : threads.values()) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return;
            try {
                t.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 返回是否真正取得许可，避免中断后仍执行并在 finally 中错误释放许可。 */
    private boolean acquire() {
        try {
            sem.acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void cancelActiveInvocations() {
        activeInvocations.forEach(future -> future.cancel(true));
        activeInvocations.clear();
    }

    private void safeEmit(StreamEvent event) {
        try {
            onEvent.accept(event);
        } catch (RuntimeException e) {
            log.debug("GraphRuntime event consumer failed: {}", e.getMessage());
        }
    }

    private static int idAsInt(String id) {
        if (id == null || id.isEmpty()) return 0;
        if (id.charAt(0) == 'n') {
            try { return Integer.parseInt(id.substring(1)); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private GraphResult buildResult() {
        GraphResult r = new GraphResult();
        r.observations = graph.executionSummaries();
        int done = 0;
        boolean degraded = false;
        for (Map.Entry<String, Node> e : graph.getNodes().entrySet()) {
            Node n = e.getValue();
            r.nodeResults.put(e.getKey(), new NodeResult(n.getStatus(), n.getResult(), n.getError()));
            if (n.getStatus() == NodeStatus.DONE) done++;
            if (n.getStatus() == NodeStatus.FAILED
                    || n.getStatus() == NodeStatus.CANCELLED
                    || (n.getStatus() == NodeStatus.SKIPPED && !n.getError().isEmpty())) {
                degraded = true;
                if (r.failureReason.isEmpty() && !n.getError().isEmpty()) r.failureReason = n.getError();
            }
        }
        if (fatalAborted.get()) {
            r.outcome = GraphOutcome.FAILED;
            r.failureReason = fatalReason;
        } else if (degraded && done == 0) {
            r.outcome = GraphOutcome.FAILED;
        } else if (degraded) {
            r.outcome = GraphOutcome.PARTIAL_SUCCESS;
        }
        return r;
    }

    private GraphResult failedResult(String reason) {
        GraphResult result = buildResult();
        result.outcome = GraphOutcome.FAILED;
        result.failureReason = reason == null ? "关键路径失败" : reason;
        return result;
    }

    private GraphResult interrupted(String msg) {
        // 把所有 pending/running 标 CANCELLED
        for (Node n : graph.getNodes().values()) {
            if (n.getStatus() == NodeStatus.PENDING || n.getStatus() == NodeStatus.RUNNING) {
                graph.setNodeStatus(n.getId(), NodeStatus.CANCELLED);
            }
        }
        GraphResult r = buildResult();
        r.outcome = GraphOutcome.INTERRUPTED;
        r.interrupted = true;
        r.interruptedMsg = msg;
        return r;
    }

    public static String buildInterruptMessage(TaskGraph g) {
        int done = 0;
        List<String> doneDesc = new ArrayList<>();
        List<String> pendingDesc = new ArrayList<>();
        for (Node n : g.getNodes().values()) {
            switch (n.getStatus()) {
                case DONE -> {
                    done++;
                    String r = n.getResult() == null ? "" : n.getResult();
                    if (r.length() > 30) r = r.substring(0, 30) + "…";
                    doneDesc.add(n.getId() + "(" + n.getToolName() + ")→" + r);
                }
                case PENDING, RUNNING, CANCELLED ->
                        pendingDesc.add(n.getId() + "(" + n.getToolName() + ")");
                default -> { /* failed/skipped 不计入未执行 */ }
            }
        }
        StringBuilder msg = new StringBuilder("已完成 ").append(done).append("/")
                .append(g.getNodes().size()).append(" 步");
        if (!doneDesc.isEmpty()) msg.append("：").append(String.join("；", doneDesc));
        if (!pendingDesc.isEmpty()) msg.append("；未执行：").append(String.join("、", pendingDesc));
        return msg.toString();
    }

    public static String buildFailureMessage(TaskGraph graph, String reason) {
        List<String> completed = new ArrayList<>();
        for (Node node : graph.getNodes().values()) {
            if (node.getStatus() == NodeStatus.DONE) {
                completed.add(node.getId() + "(" + node.executorName() + ")");
            }
        }
        StringBuilder message = new StringBuilder(reason == null || reason.isBlank()
                ? "任务关键步骤执行失败" : reason);
        if (!completed.isEmpty()) message.append("；已完成：").append(String.join("、", completed));
        return message.toString();
    }
}
