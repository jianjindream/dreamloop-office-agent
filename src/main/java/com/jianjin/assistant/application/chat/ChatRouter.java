package com.jianjin.assistant.application.chat;

import com.jianjin.assistant.model.Tool;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ChatRouter {

    private ChatRouter() {}

    /** Stable product-level office intent, independent from the execution mode. */
    public static String detectOfficeIntent(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (containsAny(q, "对比", "比较", "差异", "冲突")) return "compare";
        if (containsAny(q, "提取", "抽取", "识别", "列出", "行动项", "待办")) return "extract";
        if (containsAny(q, "总结", "汇总", "摘要", "概括")) return "summarize";
        if (containsAny(q, "润色", "改写", "优化表达", "校对")) return "rewrite";
        if (containsAny(q, "周报", "汇报", "方案", "纪要", "邮件", "起草", "撰写", "生成")) return "draft";
        if (containsAny(q, "分析", "原因", "趋势", "风险")) return "analyze";
        return "qa";
    }

    private static boolean containsAny(String value, String... tokens) {
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    /** 单一工具触发：时间 / 天气 / 搜索 / 查询 */
    public static boolean needTool(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return q.contains("几点") || q.contains("时间")
                || q.contains("天气") || q.contains("查")
                || q.contains("搜索") || q.contains("是什么");
    }

    /** 知识库已加载且本次不走工具/ReAct 时启用 RAG */
    public static boolean needRAG(String query, boolean ragLoaded) {
        return ragLoaded && !needTool(query) && !needReAct(query);
    }

    /** 当 query 涉及 2+ 个子需求时触发多步推理 */
    public static boolean needReAct(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        int count = 0;
        if (q.contains("时间") || q.contains("几点")) count++;
        if (q.contains("天气")) count++;
        if (q.contains("总结") || q.contains("汇总")) count++;
        if (q.contains("查") || q.contains("搜索")) count++;
        return count >= 2;
    }

    /** 在显式指定了工具集时直接走 ReAct 路径 */
    public static boolean needReActFromTools(String query, Map<String, Tool> ts) {
        return ts != null && !ts.isEmpty();
    }

    /** 选定模式：Explicit 模式时优先使用前端指定的 Tools/UseRAG，否则走规则。 */
    public static String decideMode(String query, boolean explicit, boolean useRag,
                                    List<String> selectedTools, boolean ragLoaded) {
        return decideMode(query, explicit, useRag, selectedTools, ragLoaded, false);
    }

    /** A selected-document scope always wins over broad keyword routing. */
    public static String decideMode(String query, boolean explicit, boolean useRag,
                                    List<String> selectedTools, boolean ragLoaded,
                                    boolean selectedDocumentScope) {
        if (explicit) {
            if (selectedTools != null && !selectedTools.isEmpty()) {
                return "react";
            }
            if (useRag && (ragLoaded || selectedDocumentScope)) return "rag";
            return "chat";
        }
        if (selectedDocumentScope) return "rag";
        if (needReAct(query)) return "react";
        if (needTool(query)) return "tool";
        if (needRAG(query, ragLoaded)) return "rag";
        return "chat";
    }
}
