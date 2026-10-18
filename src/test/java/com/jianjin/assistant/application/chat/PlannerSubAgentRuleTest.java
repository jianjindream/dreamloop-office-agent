package com.jianjin.assistant.application.chat;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.domain.graph.Node;
import com.jianjin.assistant.domain.graph.NodeType;
import com.jianjin.assistant.model.Tool;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlannerSubAgentRuleTest {

    private static Map<String, Tool> retrievalTools() {
        Tool tavily = raceSafeTool("search_web", "web");
        Tool bing = raceSafeTool("bing_search", "bing");
        Tool google = raceSafeTool("google_search", "google");
        return Map.of(
                "search_web", tavily,
                "bing_search", bing,
                "google_search", google,
                "rag_search", new Tool("rag_search", "", List.of(), params -> "rag"));
    }

    private static Tool raceSafeTool(String name, String result) {
        Tool tool = new Tool(name, "", List.of(), params -> result);
        tool.setRaceSafe(true);
        return tool;
    }

    @Test
    void researchQueryPlansSubAgents() {
        Planner planner = new Planner(new AppConfig(), null, null);
        List<Node> nodes = planner.planGraph("调研 PPO 的优势，两句话即可", new HashMap<>(), "");
        String[] want = {"research_agent", "writer_agent", "review_agent"};
        assertEquals(want.length, nodes.size(), "expected " + want.length + " sub-agent nodes");
        for (int i = 0; i < want.length; i++) {
            assertEquals(NodeType.SUB_AGENT, nodes.get(i).getType(), "node " + i + " type");
            assertEquals(want[i], nodes.get(i).getAgentName(), "node " + i + " agent");
        }
    }

    @Test
    void reportSaveQueryPlansDocAgent() {
        Planner planner = new Planner(new AppConfig(), null, null);
        List<Node> nodes = planner.planGraph("生成一份 PPO 报告并保存到本地文档库", new HashMap<>(), "");
        String[] want = {"research_agent", "writer_agent", "review_agent", "doc_agent"};
        assertEquals(want.length, nodes.size());
        for (int i = 0; i < want.length; i++) {
            assertEquals(NodeType.SUB_AGENT, nodes.get(i).getType());
            assertEquals(want[i], nodes.get(i).getAgentName());
        }
    }

    @Test
    void generalLookupUsesSearchRace() {
        Planner planner = new Planner(new AppConfig(), null, null);
        List<Node> nodes = planner.planGraph("查询 Java 是什么", retrievalTools(), "");

        assertEquals(4, nodes.size());
        assertEquals(3, nodes.stream().filter(n -> "web_search".equals(n.getRaceGroup())).count());
        Node rag = nodes.stream().filter(n -> "rag_search".equals(n.getToolName())).findFirst().orElseThrow();
        assertEquals("", rag.getRaceGroup());
    }

    @Test
    void internalKnowledgeUsesRagWithoutRacingWeb() {
        Planner planner = new Planner(new AppConfig(), null, null);
        List<Node> nodes = planner.planGraph("查询知识库中的报销规定", retrievalTools(), "");

        assertEquals(1, nodes.size());
        assertEquals("rag_search", nodes.getFirst().getToolName());
        assertEquals("", nodes.getFirst().getRaceGroup());
    }

    @Test
    void realtimeLookupRacesPublicSearchSourcesWithoutStaleRag() {
        Planner planner = new Planner(new AppConfig(), null, null);
        List<Node> nodes = planner.planGraph("查询最新 Java 版本", retrievalTools(), "");

        assertEquals(3, nodes.size());
        assertTrue(nodes.stream().allMatch(n -> "web_search".equals(n.getRaceGroup())));
        assertTrue(nodes.stream().noneMatch(n -> "rag_search".equals(n.getToolName())));
    }

    @Test
    void complementaryQueryRacesWebSourcesButKeepsRagForAggregation() {
        Planner planner = new Planner(new AppConfig(), null, null);
        List<Node> nodes = planner.planGraph("结合知识库和网络搜索查询报销规定", retrievalTools(), "");

        assertEquals(4, nodes.size());
        assertEquals(3, nodes.stream().filter(n -> "web_search".equals(n.getRaceGroup())).count());
        Node rag = nodes.stream().filter(n -> "rag_search".equals(n.getToolName())).findFirst().orElseThrow();
        assertEquals("", rag.getRaceGroup());
    }

    @Test
    void aSingleConfiguredPublicSourceRunsWithoutRaceGroup() {
        Planner planner = new Planner(new AppConfig(), null, null);
        Map<String, Tool> tools = Map.of("bing_search", raceSafeTool("bing_search", "bing"));

        List<Node> nodes = planner.planGraph("查询最新 Java 版本", tools, "");

        assertEquals(1, nodes.size());
        assertEquals("bing_search", nodes.getFirst().getToolName());
        assertEquals("", nodes.getFirst().getRaceGroup());
    }
}
