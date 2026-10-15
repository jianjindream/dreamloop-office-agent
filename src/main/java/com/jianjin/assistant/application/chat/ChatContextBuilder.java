package com.jianjin.assistant.application.chat;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.domain.promptctx.ContextAssembler;
import com.jianjin.assistant.domain.promptctx.Query;
import com.jianjin.assistant.domain.promptctx.RuntimeContext;
import com.jianjin.assistant.domain.promptctx.SourceRegistry;
import com.jianjin.assistant.domain.promptctx.Schemas;
import com.jianjin.assistant.domain.promptctx.source.ConstraintsSource;
import com.jianjin.assistant.domain.promptctx.source.PlannerSnapshot;
import com.jianjin.assistant.domain.promptctx.source.PlannerSource;
import com.jianjin.assistant.domain.promptctx.source.ProfileSource;
import com.jianjin.assistant.domain.promptctx.source.RecallSource;
import com.jianjin.assistant.domain.promptctx.source.TaskMemBuffer;
import com.jianjin.assistant.domain.promptctx.source.TaskMemSource;
import com.jianjin.assistant.domain.promptctx.source.ToolStateSource;
import com.jianjin.assistant.domain.promptctx.source.ToolStateTracker;
import com.jianjin.assistant.model.Tool;
import com.jianjin.assistant.service.llm.LlmService;
import com.jianjin.assistant.service.memory.GraphMemory;
import com.jianjin.assistant.service.memory.LongTermMemory;
import com.jianjin.assistant.service.memory.PreferenceMemory;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class ChatContextBuilder {

    private final ContextAssembler assembler;
    private final TaskMemBuffer taskMem;
    private final ToolStateTracker toolTracker;
    private final LlmService llm;

    public ChatContextBuilder(AppConfig cfg,
                              LlmService llm,
                              PreferenceMemory pref,
                              LongTermMemory ltm,
                              GraphMemory graphMem,
                              Supplier<Map<String, Tool>> toolsRegistry,
                              Supplier<PlannerSnapshot> plannerProvider) {
        this.llm = llm;
        this.taskMem = new TaskMemBuffer(20);
        this.toolTracker = new ToolStateTracker(10);

        SourceRegistry registry = new SourceRegistry();
        registry.register(new ProfileSource(pref, ltm, ltm::markRecalled));
        registry.register(new PlannerSource(plannerProvider));
        registry.register(new TaskMemSource(taskMem));
        registry.register(new ToolStateSource(toolsRegistry, toolTracker));
        registry.register(ConstraintsSource.fromBuiltinValidator());
        // 优先用图记忆作 Recaller；图层不可用时退化到 LTM
        if (graphMem != null) {
            registry.register(new RecallSource(graphMem::recall, cfg.getMemory().getLongTermTopK(), graphMem::markRecalled));
        } else {
            registry.register(new RecallSource(ltm::recall, cfg.getMemory().getLongTermTopK(), ltm::markRecalled));
        }
        this.assembler = new ContextAssembler(Schemas.defaults(), registry);
    }

    /** 装配并渲染当前 mode 下的 system prompt 前缀。 */
    public String buildPrefix(String query, String mode) {
        List<Double> emb = llm.embed(query);
        Query q = new Query(query, emb, "", mode);
        RuntimeContext rc = assembler.assemble(q);
        return rc.render();
    }

    /** 拼接记忆前缀到 base prompt 之前。 */
    public String buildSystemPrompt(String memPrefix, String basePrompt) {
        if (memPrefix == null || memPrefix.isEmpty()) return basePrompt;
        return memPrefix + "\n\n" + basePrompt;
    }

    public TaskMemBuffer taskMem() { return taskMem; }

    public ToolStateTracker toolTracker() { return toolTracker; }
}
