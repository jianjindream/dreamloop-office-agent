package com.jianjin.assistant.application.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatRouterTest {

    @Test
    void chatModeForOrdinaryQuery() {
        assertEquals("chat", ChatRouter.decideMode("你好", false, false, null, false));
    }

    @Test
    void toolModeForSingleToolKeyword() {
        assertEquals("tool", ChatRouter.decideMode("现在几点", false, false, null, false));
    }

    @Test
    void reactModeForCompoundQuery() {
        assertEquals("react", ChatRouter.decideMode(
                "查一下北京天气并搜索路况", false, false, null, false));
    }

    @Test
    void ragModeWhenLoadedAndNoToolTrigger() {
        assertEquals("rag", ChatRouter.decideMode("介绍一下这本书的主旨", false, false, null, true));
    }

    @Test
    void explicitSelectedToolsForcesReact() {
        assertEquals("react", ChatRouter.decideMode(
                "你好", true, false, List.of("get_time"), false));
    }

    @Test
    void explicitUseRagWhenLoaded() {
        assertEquals("rag", ChatRouter.decideMode("test", true, true, null, true));
    }

    @Test
    void explicitWithoutAnythingFallsBackToChat() {
        assertEquals("chat", ChatRouter.decideMode("test", true, false, null, false));
    }

    @Test
    void needToolKeywordCoverage() {
        assertTrue(ChatRouter.needTool("查询订单"));
        assertTrue(ChatRouter.needTool("帮我搜索"));
        assertTrue(ChatRouter.needTool("Java 是什么"));
        assertFalse(ChatRouter.needTool("你好"));
    }

    @Test
    void selectedDocumentsOverrideBroadToolKeywords() {
        assertEquals("rag", ChatRouter.decideMode("这个指标是什么", false, true, null, true, true));
    }

    @Test
    void detectsOfficeIntentsWithConflictTakingPriority() {
        assertEquals("compare", ChatRouter.detectOfficeIntent("总结两个版本的差异和冲突"));
        assertEquals("extract", ChatRouter.detectOfficeIntent("提取会议行动项"));
        assertEquals("draft", ChatRouter.detectOfficeIntent("撰写本周周报"));
        assertEquals("qa", ChatRouter.detectOfficeIntent("负责人是谁"));
    }
}
