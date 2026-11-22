package com.jianjin.assistant.application.chat;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.domain.graph.DependencyMode;
import com.jianjin.assistant.domain.graph.FailurePolicy;
import com.jianjin.assistant.domain.graph.Node;
import com.jianjin.assistant.domain.graph.NodeStatus;
import com.jianjin.assistant.domain.graph.NodeType;
import com.jianjin.assistant.domain.graph.TaskGraph;
import com.jianjin.assistant.model.Tool;
import com.jianjin.assistant.model.ToolParam;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphRuntime 行为冒烟测试：
 *  1) 拓扑层并行 + 依赖正确传递
 *  2) 同 race_group 节点 first-qualified / bounded-best，落败者标 SKIPPED
 *  3) 中断信号能停止后续层
 */
class GraphRuntimeTest {

    private static AppConfig minimalCfg() {
        AppConfig cfg = new AppConfig();
        cfg.getHarness().setMaxRetries(1);
        cfg.getHarness().setRetryDelayMs(1);
        cfg.getGraph().setMaxParallel(4);
        cfg.getGraph().setRaceTimeoutMs(2000);
        cfg.getGraph().setEnableRacing(true);
        return cfg;
    }

    private static Tool sleepTool(String name, long ms, String result) {
        Tool tool = new Tool(name, "sleep " + ms,
                List.of(new ToolParam("q", "string", "q", false)),
                params -> {
                    try { Thread.sleep(ms); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted");
                    }
                    return result;
                });
        tool.setRaceSafe(true);
        return tool;
    }

    private static Tool raceSafe(Tool tool) {
        tool.setRaceSafe(true);
        return tool;
    }

    @Test
    void topologicalLevelsRespectDependencies() {
        AtomicInteger order = new AtomicInteger();
        Map<String, Tool> tools = new HashMap<>();
        Map<String, Integer> seenAt = new LinkedHashMap<>();
        for (String id : List.of("a", "b", "c")) {
            tools.put(id, new Tool(id, "", List.of(),
                    params -> {
                        seenAt.put(id, order.incrementAndGet());
                        try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                        return id + "-ok";
                    }));
        }

        Node a = new Node("n1", NodeType.TOOL, "A", "a", new HashMap<>(), List.of(), "");
        Node b = new Node("n2", NodeType.TOOL, "B", "b", new HashMap<>(), List.of(), "");
        Node c = new Node("n3", NodeType.TOOL, "C", "c", new HashMap<>(), List.of("n1", "n2"), "");
        TaskGraph tg = new TaskGraph(List.of(a, b, c));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        GraphRuntime rt = new GraphRuntime(tg, minimalCfg(), GraphRuntime.Config.from(minimalCfg()),
                tools, cancelled, e -> {});
        GraphRuntime.GraphResult r = rt.execute();

        assertFalse(r.interrupted);
        assertEquals(NodeStatus.DONE, tg.getNodes().get("n1").getStatus());
        assertEquals(NodeStatus.DONE, tg.getNodes().get("n2").getStatus());
        assertEquals(NodeStatus.DONE, tg.getNodes().get("n3").getStatus());
        assertTrue(seenAt.get("c") > seenAt.get("a"), "c must run after a");
        assertTrue(seenAt.get("c") > seenAt.get("b"), "c must run after b");
    }

    @Test
    void raceGroupKeepsFirstSuccess() {
        Map<String, Tool> tools = new HashMap<>();
        tools.put("fast", sleepTool("fast", 30, "fast-result"));
        tools.put("slow", sleepTool("slow", 300, "slow-result"));

        Node fast = new Node("n1", NodeType.TOOL, "fast", "fast", new HashMap<>(), List.of(), "search");
        Node slow = new Node("n2", NodeType.TOOL, "slow", "slow", new HashMap<>(), List.of(), "search");
        TaskGraph tg = new TaskGraph(List.of(fast, slow));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        GraphRuntime rt = new GraphRuntime(tg, minimalCfg(), GraphRuntime.Config.from(minimalCfg()),
                tools, cancelled, e -> {});
        GraphRuntime.GraphResult r = rt.execute();

        assertFalse(r.interrupted);
        assertEquals(NodeStatus.DONE, tg.getNodes().get("n1").getStatus());
        // 慢节点要么没机会跑完被中断，要么直接 SKIPPED；总之不应是 DONE
        assertNotEquals(NodeStatus.DONE, tg.getNodes().get("n2").getStatus());
        assertEquals("fast-result", tg.getNodes().get("n1").getResult());
    }

    @Test
    void fastestExplicitNoResultDoesNotWinRetrievalRace() {
        AppConfig cfg = minimalCfg();
        cfg.getGraph().setRaceGraceMs(30);
        Map<String, Tool> tools = new HashMap<>();
        tools.put("search_web", sleepTool("search_web", 10, "未找到相关内容"));
        tools.put("rag_search", sleepTool("rag_search", 40,
                "知识库证据：项目采用 DAG 调度，并支持依赖驱动的并行执行。"));
        Node web = new Node("n1", NodeType.TOOL, "web", "search_web",
                new HashMap<>(), List.of(), "search");
        Node rag = new Node("n2", NodeType.TOOL, "rag", "rag_search",
                new HashMap<>(), List.of(), "search");

        new GraphRuntime(new TaskGraph(List.of(web, rag)), cfg, GraphRuntime.Config.from(cfg),
                tools, new AtomicBoolean(false), e -> {}).execute();

        assertEquals(NodeStatus.SKIPPED, web.getStatus());
        assertEquals(NodeStatus.DONE, rag.getStatus());
        assertTrue(rag.getResult().contains("DAG"));
    }

    @Test
    void graceWindowCanPreferHigherQualityRetrievalResult() {
        AppConfig cfg = minimalCfg();
        cfg.getGraph().setRaceGraceMs(100);
        cfg.getGraph().setRaceMinResultChars(5);
        Map<String, Tool> tools = new HashMap<>();
        tools.put("search_web", sleepTool("search_web", 10, "这是一个可用但较短的搜索答案。"));
        tools.put("rag_search", sleepTool("rag_search", 40,
                "来源：https://example.test/doc\n- 更完整的证据一\n- 更完整的证据二，并包含可追溯来源。"));
        Node web = new Node("n1", NodeType.TOOL, "web", "search_web",
                new HashMap<>(), List.of(), "search");
        Node rag = new Node("n2", NodeType.TOOL, "rag", "rag_search",
                new HashMap<>(), List.of(), "search");

        new GraphRuntime(new TaskGraph(List.of(web, rag)), cfg, GraphRuntime.Config.from(cfg),
                tools, new AtomicBoolean(false), e -> {}).execute();

        assertEquals(NodeStatus.SKIPPED, web.getStatus());
        assertEquals(NodeStatus.DONE, rag.getStatus());
    }

    @Test
    void unsafeToolsWithRaceGroupDegradeToOrdinaryParallelExecution() {
        Tool leftTool = new Tool("left", "", List.of(), params -> "left-result");
        Tool rightTool = new Tool("right", "", List.of(), params -> "right-result");
        Node left = new Node("n1", NodeType.TOOL, "left", "left",
                new HashMap<>(), List.of(), "unsafe");
        Node right = new Node("n2", NodeType.TOOL, "right", "right",
                new HashMap<>(), List.of(), "unsafe");

        new GraphRuntime(new TaskGraph(List.of(left, right)), minimalCfg(),
                GraphRuntime.Config.from(minimalCfg()), Map.of("left", leftTool, "right", rightTool),
                new AtomicBoolean(false), e -> {}).execute();

        assertEquals(NodeStatus.DONE, left.getStatus());
        assertEquals(NodeStatus.DONE, right.getStatus());
    }

    @Test
    void cancelStopsScheduling() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Map<String, Tool> tools = new HashMap<>();
        tools.put("a", new Tool("a", "", List.of(),
                params -> { cancelled.set(true); return "a"; }));
        tools.put("b", sleepTool("b", 200, "b"));

        Node a = new Node("n1", NodeType.TOOL, "A", "a", new HashMap<>(), List.of(), "");
        Node b = new Node("n2", NodeType.TOOL, "B", "b", new HashMap<>(), List.of("n1"), "");
        TaskGraph tg = new TaskGraph(List.of(a, b));

        GraphRuntime rt = new GraphRuntime(tg, minimalCfg(), GraphRuntime.Config.from(minimalCfg()),
                tools, cancelled, e -> {});
        GraphRuntime.GraphResult r = rt.execute();

        assertTrue(r.interrupted);
        assertEquals(NodeStatus.DONE, tg.getNodes().get("n1").getStatus());
        // b 在层进入前被取消 → CANCELLED
        assertEquals(NodeStatus.CANCELLED, tg.getNodes().get("n2").getStatus());
    }

    @Test
    void failedDependencySkipsDownstreamAndKeepsFailureInSummary() {
        AtomicInteger downstreamCalls = new AtomicInteger();
        Map<String, Tool> tools = new HashMap<>();
        tools.put("fail", new Tool("fail", "", List.of(), params -> {
            throw new RuntimeException("upstream boom");
        }));
        tools.put("downstream", new Tool("downstream", "", List.of(), params -> {
            downstreamCalls.incrementAndGet();
            return "must-not-run";
        }));

        Node first = new Node("n1", NodeType.TOOL, "fail", "fail",
                new HashMap<>(), List.of(), "");
        Node second = new Node("n2", NodeType.TOOL, "downstream", "downstream",
                new HashMap<>(), List.of("n1"), "");
        TaskGraph tg = new TaskGraph(List.of(first, second));

        GraphRuntime.GraphResult result = new GraphRuntime(tg, minimalCfg(),
                GraphRuntime.Config.from(minimalCfg()), tools, new AtomicBoolean(false), e -> {}).execute();

        assertEquals(NodeStatus.FAILED, first.getStatus());
        assertEquals(NodeStatus.SKIPPED, second.getStatus());
        assertEquals(0, downstreamCalls.get());
        assertTrue(second.getError().contains("n1(failed)"));
        assertTrue(result.observations.stream().anyMatch(s -> s.contains("upstream boom")));
        assertTrue(result.observations.stream().anyMatch(s -> s.contains("上游依赖未成功")));
        assertEquals(GraphRuntime.GraphOutcome.FAILED, result.outcome);
    }

    @Test
    void failedAllDependencySkipsDescendantWithoutWaitingForOtherSlowDependency() throws Exception {
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch descendantSkipped = new CountDownLatch(1);
        Map<String, Tool> tools = new HashMap<>();
        tools.put("fail", new Tool("fail", "", List.of(), params -> {
            throw new RuntimeException("boom");
        }));
        tools.put("slow", new Tool("slow", "", List.of(), params -> {
            try { releaseSlow.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "slow";
        }));
        tools.put("consumer", new Tool("consumer", "", List.of(), params -> "must-not-run"));

        Node failed = new Node("n1", NodeType.TOOL, "fail", "fail", new HashMap<>(), List.of(), "");
        Node slow = new Node("n2", NodeType.TOOL, "slow", "slow", new HashMap<>(), List.of(), "");
        Node consumer = new Node("n3", NodeType.TOOL, "consumer", "consumer",
                new HashMap<>(), List.of("n1", "n2"), "");
        TaskGraph graph = new TaskGraph(List.of(failed, slow, consumer));

        Thread execution = Thread.ofVirtual().start(() -> new GraphRuntime(graph, minimalCfg(),
                GraphRuntime.Config.from(minimalCfg()), tools, new AtomicBoolean(false), event -> {
                    if ("node_done".equals(event.type()) && event.data() instanceof Map<?, ?> data
                            && "n3".equals(data.get("id")) && "skipped".equals(data.get("status"))) {
                        descendantSkipped.countDown();
                    }
                }).execute());

        assertTrue(descendantSkipped.await(1, TimeUnit.SECONDS),
                "descendant should be skipped as soon as one mandatory dependency fails");
        assertNotEquals(NodeStatus.DONE, slow.getStatus());
        releaseSlow.countDown();
        execution.join(2000);
        assertEquals(NodeStatus.SKIPPED, consumer.getStatus());
    }

    @Test
    void failFastNodeCancelsIndependentSlowBranch() {
        CountDownLatch slowStarted = new CountDownLatch(1);
        Map<String, Tool> tools = new HashMap<>();
        tools.put("critical", new Tool("critical", "", List.of(), params -> {
            try { slowStarted.await(1, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            throw new RuntimeException("critical boom");
        }));
        tools.put("slow", new Tool("slow", "", List.of(), params -> {
            slowStarted.countDown();
            try { Thread.sleep(5000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException("interrupted"); }
            return "late";
        }));
        Node critical = new Node("n1", NodeType.TOOL, "critical", "critical",
                new HashMap<>(), List.of(), "");
        critical.setFailurePolicy(FailurePolicy.FAIL_FAST);
        Node slow = new Node("n2", NodeType.TOOL, "slow", "slow", new HashMap<>(), List.of(), "");

        long startedAt = System.nanoTime();
        GraphRuntime.GraphResult result = new GraphRuntime(new TaskGraph(List.of(critical, slow)),
                minimalCfg(), GraphRuntime.Config.from(minimalCfg()), tools,
                new AtomicBoolean(false), event -> {}).execute();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertEquals(GraphRuntime.GraphOutcome.FAILED, result.outcome);
        assertFalse(result.interrupted);
        assertTrue(result.failureReason.contains("关键节点"));
        assertTrue(elapsedMs < 1500, "critical failure should not wait for the slow branch");
        assertEquals(NodeStatus.CANCELLED, slow.getStatus());
    }

    @Test
    void unreachableRequiredOutputCancelsIndependentSlowBranch() {
        CountDownLatch slowStarted = new CountDownLatch(1);
        Map<String, Tool> tools = new HashMap<>();
        tools.put("source", new Tool("source", "", List.of(), params -> {
            try { slowStarted.await(1, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            throw new RuntimeException("source boom");
        }));
        tools.put("output", new Tool("output", "", List.of(), params -> "output"));
        tools.put("slow", new Tool("slow", "", List.of(), params -> {
            slowStarted.countDown();
            try { Thread.sleep(5000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException("interrupted"); }
            return "late";
        }));
        Node source = new Node("n1", NodeType.TOOL, "source", "source", new HashMap<>(), List.of(), "");
        Node output = new Node("n2", NodeType.TOOL, "output", "output",
                new HashMap<>(), List.of("n1"), "");
        output.setRequiredOutput(true);
        Node slow = new Node("n3", NodeType.TOOL, "slow", "slow", new HashMap<>(), List.of(), "");

        GraphRuntime.GraphResult result = new GraphRuntime(new TaskGraph(List.of(source, output, slow)),
                minimalCfg(), GraphRuntime.Config.from(minimalCfg()), tools,
                new AtomicBoolean(false), event -> {}).execute();

        assertEquals(GraphRuntime.GraphOutcome.FAILED, result.outcome);
        assertTrue(result.failureReason.contains("必需输出"));
        assertEquals(NodeStatus.SKIPPED, output.getStatus());
        assertEquals(NodeStatus.CANCELLED, slow.getStatus());
    }

    @Test
    void anyDependencyStartsAfterFirstSuccess() {
        CountDownLatch consumerRan = new CountDownLatch(1);
        AtomicBoolean slowReleasedByConsumer = new AtomicBoolean(false);
        Map<String, Tool> tools = new HashMap<>();
        tools.put("fast", new Tool("fast", "", List.of(), params -> "fast"));
        tools.put("slow", new Tool("slow", "", List.of(), params -> {
            try { slowReleasedByConsumer.set(consumerRan.await(1, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "slow";
        }));
        tools.put("consumer", new Tool("consumer", "", List.of(), params -> {
            consumerRan.countDown();
            return "combined";
        }));
        Node fast = new Node("n1", NodeType.TOOL, "fast", "fast", new HashMap<>(), List.of(), "");
        Node slow = new Node("n2", NodeType.TOOL, "slow", "slow", new HashMap<>(), List.of(), "");
        Node consumer = new Node("n3", NodeType.TOOL, "consumer", "consumer",
                new HashMap<>(), List.of("n1", "n2"), "");
        consumer.setDependencyMode(DependencyMode.ANY);

        new GraphRuntime(new TaskGraph(List.of(fast, slow, consumer)), minimalCfg(),
                GraphRuntime.Config.from(minimalCfg()), tools, new AtomicBoolean(false), event -> {}).execute();

        assertTrue(slowReleasedByConsumer.get(), "ANY consumer should not wait for every dependency");
        assertEquals(NodeStatus.DONE, consumer.getStatus());
    }

    @Test
    void quorumDependencyRunsWhenThresholdIsReached() {
        Map<String, Tool> tools = new HashMap<>();
        tools.put("ok-a", new Tool("ok-a", "", List.of(), params -> "a"));
        tools.put("ok-b", new Tool("ok-b", "", List.of(), params -> "b"));
        tools.put("fail", new Tool("fail", "", List.of(), params -> { throw new RuntimeException("boom"); }));
        tools.put("consumer", new Tool("consumer", "", List.of(), params -> "combined"));
        Node a = new Node("n1", NodeType.TOOL, "a", "ok-a", new HashMap<>(), List.of(), "");
        Node b = new Node("n2", NodeType.TOOL, "b", "ok-b", new HashMap<>(), List.of(), "");
        Node failed = new Node("n3", NodeType.TOOL, "failed", "fail", new HashMap<>(), List.of(), "");
        Node consumer = new Node("n4", NodeType.TOOL, "consumer", "consumer",
                new HashMap<>(), List.of("n1", "n2", "n3"), "");
        consumer.setDependencyMode(DependencyMode.QUORUM);
        consumer.setQuorum(2);

        GraphRuntime.GraphResult result = new GraphRuntime(new TaskGraph(List.of(a, b, failed, consumer)),
                minimalCfg(), GraphRuntime.Config.from(minimalCfg()), tools,
                new AtomicBoolean(false), event -> {}).execute();

        assertEquals(NodeStatus.DONE, consumer.getStatus());
        assertEquals(GraphRuntime.GraphOutcome.PARTIAL_SUCCESS, result.outcome);
    }

    @Test
    void simultaneousRaceSuccessesStillCommitExactlyOneWinner() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Map<String, Tool> tools = new HashMap<>();
        for (String name : List.of("left", "right")) {
            tools.put(name, raceSafe(new Tool(name, "", List.of(), params -> {
                try {
                    barrier.await(1, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return name + "-result";
            })));
        }

        Node left = new Node("n1", NodeType.TOOL, "left", "left",
                new HashMap<>(), List.of(), "search");
        Node right = new Node("n2", NodeType.TOOL, "right", "right",
                new HashMap<>(), List.of(), "search");
        TaskGraph tg = new TaskGraph(List.of(left, right));

        new GraphRuntime(tg, minimalCfg(), GraphRuntime.Config.from(minimalCfg()),
                tools, new AtomicBoolean(false), e -> {}).execute();

        long done = tg.getNodes().values().stream().filter(n -> n.getStatus() == NodeStatus.DONE).count();
        long skipped = tg.getNodes().values().stream().filter(n -> n.getStatus() == NodeStatus.SKIPPED).count();
        assertEquals(1, done);
        assertEquals(1, skipped);
    }

    @Test
    void raceTimeoutCannotBeOverwrittenByLateToolCompletion() throws Exception {
        AppConfig cfg = minimalCfg();
        cfg.getGraph().setRaceTimeoutMs(30);
        Map<String, Tool> tools = new HashMap<>();
        for (String name : List.of("slow-a", "slow-b")) {
            tools.put(name, raceSafe(new Tool(name, "", List.of(), params -> {
                long deadline = System.currentTimeMillis() + 500;
                while (System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(10); } catch (InterruptedException ignored) { /* deliberately stubborn */ }
                }
                return name + "-late";
            })));
        }

        Node a = new Node("n1", NodeType.TOOL, "slow-a", "slow-a",
                new HashMap<>(), List.of(), "search");
        Node b = new Node("n2", NodeType.TOOL, "slow-b", "slow-b",
                new HashMap<>(), List.of(), "search");
        TaskGraph tg = new TaskGraph(List.of(a, b));

        new GraphRuntime(tg, cfg, GraphRuntime.Config.from(cfg), tools,
                new AtomicBoolean(false), e -> {}).execute();
        assertEquals(NodeStatus.FAILED, a.getStatus());
        assertEquals(NodeStatus.FAILED, b.getStatus());

        Thread.sleep(550);
        assertEquals(NodeStatus.FAILED, a.getStatus());
        assertEquals(NodeStatus.FAILED, b.getStatus());
        assertTrue(a.getResult().isEmpty());
        assertTrue(b.getResult().isEmpty());
    }

    @Test
    void singleNodeHonorsConfiguredStepTimeout() {
        AppConfig cfg = minimalCfg();
        cfg.getHarness().setStepTimeoutMs(30);
        Map<String, Tool> tools = Map.of("slow", sleepTool("slow", 500, "late"));
        Node slow = new Node("n1", NodeType.TOOL, "slow", "slow",
                new HashMap<>(), List.of(), "");
        TaskGraph tg = new TaskGraph(List.of(slow));

        new GraphRuntime(tg, cfg, GraphRuntime.Config.from(cfg), tools,
                new AtomicBoolean(false), e -> {}).execute();

        assertEquals(NodeStatus.FAILED, slow.getStatus());
        assertTrue(slow.getError().contains("执行超时"));
        assertTrue(slow.getResult().isEmpty());
    }

    @Test
    void toolParamsCanReferenceDependencyResults() {
        Map<String, Tool> tools = new HashMap<>();
        tools.put("source", new Tool("source", "", List.of(), params -> "upstream-value"));
        tools.put("consumer", new Tool("consumer", "", List.of(),
                params -> "received:" + params.get("text")));
        Node source = new Node("n1", NodeType.TOOL, "source", "source",
                new HashMap<>(), List.of(), "");
        Node consumer = new Node("n2", NodeType.TOOL, "consumer", "consumer",
                new HashMap<>(Map.of("text", "{{n1.result}}")), List.of("n1"), "");
        TaskGraph tg = new TaskGraph(List.of(source, consumer));

        new GraphRuntime(tg, minimalCfg(), GraphRuntime.Config.from(minimalCfg()), tools,
                new AtomicBoolean(false), e -> {}).execute();

        assertEquals(NodeStatus.DONE, consumer.getStatus());
        assertEquals("received:upstream-value", consumer.getResult());
    }

    @Test
    void raceWinnerSatisfiesDependenciesOnSkippedPeers() {
        Map<String, Tool> tools = new HashMap<>();
        tools.put("fast", sleepTool("fast", 10, "winner-value"));
        tools.put("slow", sleepTool("slow", 500, "late-value"));
        tools.put("consumer", new Tool("consumer", "", List.of(),
                params -> "received:" + params.get("text")));
        Node fast = new Node("n1", NodeType.TOOL, "fast", "fast",
                new HashMap<>(), List.of(), "search");
        Node slow = new Node("n2", NodeType.TOOL, "slow", "slow",
                new HashMap<>(), List.of(), "search");
        Node consumer = new Node("n3", NodeType.TOOL, "consumer", "consumer",
                new HashMap<>(Map.of("text", "{{n2.result}}")), List.of("n1", "n2"), "");
        TaskGraph tg = new TaskGraph(List.of(fast, slow, consumer));

        new GraphRuntime(tg, minimalCfg(), GraphRuntime.Config.from(minimalCfg()), tools,
                new AtomicBoolean(false), e -> {}).execute();

        assertEquals(NodeStatus.DONE, fast.getStatus());
        assertEquals(NodeStatus.SKIPPED, slow.getStatus());
        assertEquals(NodeStatus.DONE, consumer.getStatus());
        assertEquals("received:winner-value", consumer.getResult());
    }

    @Test
    void downstreamStartsWithoutWaitingForUnrelatedSlowPeer() {
        CountDownLatch downstreamRan = new CountDownLatch(1);
        AtomicBoolean slowWasReleasedByDownstream = new AtomicBoolean(false);
        Map<String, Tool> tools = new HashMap<>();
        tools.put("slow", new Tool("slow", "", List.of(), params -> {
            try {
                slowWasReleasedByDownstream.set(downstreamRan.await(1, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "slow";
        }));
        tools.put("fast", new Tool("fast", "", List.of(), params -> "fast"));
        tools.put("downstream", new Tool("downstream", "", List.of(), params -> {
            downstreamRan.countDown();
            return "downstream";
        }));

        Node slow = new Node("n1", NodeType.TOOL, "slow", "slow", new HashMap<>(), List.of(), "");
        Node fast = new Node("n2", NodeType.TOOL, "fast", "fast", new HashMap<>(), List.of(), "");
        Node downstream = new Node("n3", NodeType.TOOL, "downstream", "downstream",
                new HashMap<>(), List.of("n2"), "");

        new GraphRuntime(new TaskGraph(List.of(slow, fast, downstream)), minimalCfg(),
                GraphRuntime.Config.from(minimalCfg()), tools, new AtomicBoolean(false), e -> {}).execute();

        assertTrue(slowWasReleasedByDownstream.get(),
                "downstream should start as soon as its own dependency completes");
        assertEquals(NodeStatus.DONE, downstream.getStatus());
    }

    @Test
    void nonRetryableToolIsNeverRepeated() {
        AppConfig cfg = minimalCfg();
        cfg.getHarness().setMaxRetries(3);
        AtomicInteger calls = new AtomicInteger();
        Tool sideEffect = new Tool("side-effect", "", List.of(), params -> {
            calls.incrementAndGet();
            throw new RuntimeException("failed after side effect");
        });
        Node node = new Node("n1", NodeType.TOOL, "side-effect", "side-effect",
                new HashMap<>(), List.of(), "");

        new GraphRuntime(new TaskGraph(List.of(node)), cfg, GraphRuntime.Config.from(cfg),
                Map.of("side-effect", sideEffect), new AtomicBoolean(false), e -> {}).execute();

        assertEquals(1, calls.get());
        assertEquals(0, node.getRetryCount());
        assertEquals(NodeStatus.FAILED, node.getStatus());
    }

    @Test
    void retryableToolUsesInitialAttemptPlusConfiguredRetries() {
        AppConfig cfg = minimalCfg();
        cfg.getHarness().setMaxRetries(2);
        AtomicInteger calls = new AtomicInteger();
        Tool retryable = new Tool("retryable", "", List.of(), true, params -> {
            if (calls.incrementAndGet() < 3) throw new RuntimeException("transient");
            return "ok";
        });
        Node node = new Node("n1", NodeType.TOOL, "retryable", "retryable",
                new HashMap<>(), List.of(), "");

        new GraphRuntime(new TaskGraph(List.of(node)), cfg, GraphRuntime.Config.from(cfg),
                Map.of("retryable", retryable), new AtomicBoolean(false), e -> {}).execute();

        assertEquals(3, calls.get());
        assertEquals(2, node.getRetryCount());
        assertEquals(NodeStatus.DONE, node.getStatus());
    }

    @Test
    void executionContextIsPassedOnlyToContextAwareTools() {
        AtomicReference<Object> owner = new AtomicReference<>();
        AtomicReference<Object> plainOwner = new AtomicReference<>();
        Tool scoped = new Tool("scoped", "", List.of(), false, true,
                params -> { owner.set(params.get(Tool.CONTEXT_OWNER_KEY)); return "ok"; });
        Tool plain = new Tool("plain", "", List.of(),
                params -> { plainOwner.set(params.get(Tool.CONTEXT_OWNER_KEY)); return "ok"; });
        Node first = new Node("n1", NodeType.TOOL, "scoped", "scoped",
                new HashMap<>(), List.of(), "");
        Node second = new Node("n2", NodeType.TOOL, "plain", "plain",
                new HashMap<>(), List.of(), "");

        new GraphRuntime(new TaskGraph(List.of(first, second)), minimalCfg(),
                GraphRuntime.Config.from(minimalCfg()), Map.of("scoped", scoped, "plain", plain),
                null, "query", "workspace:user", new AtomicBoolean(false), e -> {}).execute();

        assertEquals("workspace:user", owner.get());
        assertNull(plainOwner.get());
    }

    @Test
    void cancellationInterruptsRaceWithoutWaitingForRaceTimeout() throws Exception {
        AppConfig cfg = minimalCfg();
        cfg.getGraph().setRaceTimeoutMs(5000);
        cfg.getHarness().setStepTimeoutMs(5000);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(2);
        Map<String, Tool> tools = new HashMap<>();
        for (String name : List.of("left", "right")) {
            tools.put(name, raceSafe(new Tool(name, "", List.of(), params -> {
                started.countDown();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted");
                }
                return name;
            })));
        }
        Node left = new Node("n1", NodeType.TOOL, "left", "left",
                new HashMap<>(), List.of(), "search");
        Node right = new Node("n2", NodeType.TOOL, "right", "right",
                new HashMap<>(), List.of(), "search");
        Thread canceller = Thread.ofVirtual().start(() -> {
            try {
                started.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cancelled.set(true);
        });

        long startedAt = System.nanoTime();
        GraphRuntime.GraphResult result = new GraphRuntime(new TaskGraph(List.of(left, right)), cfg,
                GraphRuntime.Config.from(cfg), tools, cancelled, e -> {}).execute();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        canceller.join();

        assertTrue(result.interrupted);
        assertTrue(elapsedMs < 1500, "cancellation should not wait for the 5 second race timeout");
        assertEquals(NodeStatus.CANCELLED, left.getStatus());
        assertEquals(NodeStatus.CANCELLED, right.getStatus());
    }
}
