package com.jianjin.assistant.domain.promptctx;

import com.jianjin.assistant.domain.promptctx.source.ConstraintsSource;
import com.jianjin.assistant.domain.promptctx.source.ProfileSource;
import com.jianjin.assistant.domain.promptctx.source.RecallSource;
import com.jianjin.assistant.model.MemoryItem;
import com.jianjin.assistant.service.memory.LongTermMemory;
import com.jianjin.assistant.service.memory.PreferenceMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextAssemblerTest {

    @Test
    void assemblesProfileSlotFromPreferences() {
        PreferenceMemory pref = new PreferenceMemory();
        pref.save("姓名", "张三");
        pref.save("城市", "北京");
        LongTermMemory ltm = new LongTermMemory();

        SourceRegistry reg = new SourceRegistry();
        reg.register(new ProfileSource(pref, ltm));

        ContextAssembler asm = new ContextAssembler(Schemas.defaults(), reg);
        RuntimeContext rc = asm.assemble(new Query("你好", List.of(), "", "chat"));
        String rendered = rc.render();
        assertTrue(rendered.contains("用户画像"), "渲染应包含 Profile 标题");
        assertTrue(rendered.contains("姓名: 张三"), "渲染应包含偏好内容");
        assertTrue(rendered.contains("城市: 北京"));
    }

    @Test
    void respectsGlobalBudget() {
        PreferenceMemory pref = new PreferenceMemory();
        for (int i = 0; i < 200; i++) {
            pref.save("key" + i, "value" + i + "_".repeat(50));
        }
        LongTermMemory ltm = new LongTermMemory();

        SourceRegistry reg = new SourceRegistry();
        reg.register(new ProfileSource(pref, ltm));

        ContextAssembler asm = new ContextAssembler(Schemas.defaults(), reg);
        RuntimeContext rc = asm.assemble(new Query("test", List.of(), "", "chat"));
        // 全局预算 2400 字符；不应远超
        assertTrue(rc.render().length() < 3000, "应受全局预算约束");
    }

    @Test
    void unknownModeFallsBackToChat() {
        SourceRegistry reg = new SourceRegistry();
        ContextAssembler asm = new ContextAssembler(Schemas.defaults(), reg);
        RuntimeContext rc = asm.assemble(new Query("test", List.of(), "", "no-such-mode"));
        assertEquals("chat", rc.getSchema().getMode());
    }

    @Test
    void profileAndRecallRespectConfiguredCategories() {
        PreferenceMemory pref = new PreferenceMemory();
        LongTermMemory ltm = new LongTermMemory();
        ltm.storeItem(classified(1, "用户喜欢中文", "preference", 0.9));
        ltm.storeItem(classified(2, "内部普通事实", "general", 0.9));

        SourceRegistry reg = new SourceRegistry();
        reg.register(new ProfileSource(pref, ltm));
        reg.register(new RecallSource((query, topK, embedding) -> ltm.getItems()));
        ContextAssembler asm = new ContextAssembler(Schemas.defaults(), reg);

        String rendered = asm.assemble(new Query("测试", List.of(), "", "chat")).render();

        assertTrue(rendered.contains("用户喜欢中文"));
        assertTrue(rendered.contains("内部普通事实"), "general 记忆应进入 Recall");
        String profile = rendered.substring(rendered.indexOf("【用户画像】"), rendered.indexOf("【相关回忆】"));
        assertFalse(profile.contains("内部普通事实"), "general 记忆不应混入 Profile");
    }

    @Test
    void constraintsSourceRendersBlocksAndWarns() {
        SourceRegistry reg = new SourceRegistry();
        reg.register(ConstraintsSource.fromBuiltinValidator());
        ContextAssembler asm = new ContextAssembler(Schemas.defaults(), reg);
        RuntimeContext rc = asm.assemble(new Query("test", List.of(), "", "react"));
        String rendered = rc.render();
        assertTrue(rendered.contains("硬性约束"));
        assertTrue(rendered.contains("禁止 sudo") || rendered.contains("禁止"));
    }

    private static MemoryItem classified(int id, String content, String category, double score) {
        MemoryItem item = new MemoryItem(id, content, 0.8, List.of());
        item.setCategory(category);
        item.setScore(score);
        return item;
    }
}
