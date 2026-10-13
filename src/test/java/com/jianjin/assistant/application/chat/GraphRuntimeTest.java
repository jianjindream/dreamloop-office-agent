package com.jianjin.assistant.application.chat;

import com.jianjin.assistant.config.AppConfig;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphRuntime 行为冒烟测试：
 *  1) 拓扑层并行 + 依赖正确传递
 *  2) 同 race_group 节点 first-success-wins，落败者标 SKIPPED
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
        return new Tool(name, "sleep " + ms,
                List.of(new ToolParam("q", "string", "q", false)),
                params -> {
                    try { Thread.sleep(ms); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted");
                    }
                    return result;
                });
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
    }

    @Test
    void simultaneousRaceSuccessesStillCommitExactlyOneWinner() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Map<String, Tool> tools = new HashMap<>();
        for (String name : List.of("left", "right")) {
            tools.put(name, new Tool(name, "", List.of(), params -> {
                try {
                    barrier.await(1, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return name + "-result";
            }));
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
            tools.put(name, new Tool(name, "", List.of(), params -> {
                long deadline = System.currentTimeMillis() + 500;
                while (System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(10); } catch (InterruptedException ignored) { /* deliberately stubborn */ }
                }
                return name + "-late";
            }));
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
}
