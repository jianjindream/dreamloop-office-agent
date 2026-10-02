package com.jianjin.assistant.service.agent;

import com.jianjin.assistant.application.chat.ChatGenerator;
import com.jianjin.assistant.application.chat.ChatHistoryAdapter;
import com.jianjin.assistant.application.chat.ChatRouter;
import com.jianjin.assistant.application.chat.MemoryWriter;
import com.jianjin.assistant.application.chat.Planner;
import com.jianjin.assistant.application.chat.ReActLoop;
import com.jianjin.assistant.application.chat.SnapshotManager;
import com.jianjin.assistant.application.chat.StreamEvent;
import com.jianjin.assistant.application.chat.ToolModeHandler;
import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.domain.rag.HistoryMessage;
import com.jianjin.assistant.dto.ChatRequest;
import com.jianjin.assistant.dto.ChatResponse;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.infrastructure.tool.TavilyClient;
import com.jianjin.assistant.model.MemoryItem;
import com.jianjin.assistant.model.Snapshot;
import com.jianjin.assistant.model.Tool;
import com.jianjin.assistant.model.ToolParam;
import com.jianjin.assistant.service.graph.KGStore;
import com.jianjin.assistant.service.llm.LlmService;
import com.jianjin.assistant.service.memory.GraphMemory;
import com.jianjin.assistant.service.memory.LongTermMemory;
import com.jianjin.assistant.service.memory.PreferenceMemory;
import com.jianjin.assistant.service.memory.ShortTermMemory;
import com.jianjin.assistant.service.memory.MemoryScope;
import com.jianjin.assistant.service.memory.MemorySpaceManager;
import com.jianjin.assistant.service.memory.SessionMemoryCoordinator;
import com.jianjin.assistant.service.memory.SessionMemoryState;
import com.jianjin.assistant.service.memory.UserMemorySpace;
import com.jianjin.assistant.service.rag.RagService;
import com.jianjin.assistant.domain.sandbox.ExecResult;
import com.jianjin.assistant.domain.sandbox.Sandbox;
import com.jianjin.assistant.infrastructure.sandbox.SandboxFactory;
import com.jianjin.assistant.service.tools.ExecCommandTool;
import com.jianjin.assistant.service.tools.ToolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * UnifiedAgentService —— 已经从 854 行的上帝类拆分为多个 application/chat 协作类。
 *
 * <p>本类现在只承担：</p>
 * <ul>
 *   <li>启动期把所有依赖装配起来（PostConstruct）</li>
 *   <li>对外暴露 {@code process(...)} / {@code processStream(...)} 入口</li>
 *   <li>暴露 accessor（被 controller 用作只读窥视点）</li>
 * </ul>
 *
 * <p>核心循环、规划、生成、快照、记忆写入、模式路由 —— 全部移到了
 * {@code com.agi.assistant.application.chat.*}：</p>
 * <ul>
 *   <li>{@link ChatRouter} —— 模式路由</li>
 *   <li>{@link Planner} —— 任务规划</li>
 *   <li>{@link ReActLoop} —— ReAct 多步循环（同步 + 流式）</li>
 *   <li>{@link ToolModeHandler} —— 单步工具模式</li>
 *   <li>{@link ChatGenerator} —— 综合生成</li>
 *   <li>{@link SnapshotManager} —— 快照</li>
 *   <li>{@link MemoryWriter} —— 回复后的记忆 LLM 分类与写入</li>
 *   <li>{@link ChatHistoryAdapter} —— STM → LLM 消息列表</li>
 * </ul>
 */
@Service
public class UnifiedAgentService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedAgentService.class);
    private static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final AppConfig cfg;
    private final LlmService llm;
    private final RagService rag;
    private final ToolService toolService;
    private final MemorySpaceManager memorySpaces;
    private final SessionMemoryCoordinator sessionMemory;
    private final InfrastructureService infra;

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /** 知识图谱（RAG 三路融合 + 记忆图共享） */
    private KGStore kg;
    /** 图增强长期记忆 */
    /** 沙箱执行 */
    private Sandbox sandbox;

    // ----- application/chat 协作者（在 init 中实例化） -----
    private MemoryWriter memoryWriter;
    private SnapshotManager snapshotManager;
    private Planner planner;
    private ChatGenerator generator;
    private ReActLoop reactLoop;
    private com.jianjin.assistant.application.chat.subagent.SubAgentRegistry subAgents;
    private final com.jianjin.assistant.service.document.DocumentLibraryService library;

    public UnifiedAgentService(AppConfig cfg, LlmService llm, RagService rag, ToolService toolService,
                               MemorySpaceManager memorySpaces, SessionMemoryCoordinator sessionMemory,
                               InfrastructureService infra,
                               com.jianjin.assistant.service.document.DocumentLibraryService library) {
        this.cfg = cfg;
        this.llm = llm;
        this.rag = rag;
        this.toolService = toolService;
        this.memorySpaces = memorySpaces;
        this.sessionMemory = sessionMemory;
        this.infra = infra;
        this.library = library;
    }

    @PostConstruct
    public void init() {
        tools.putAll(toolService.getDefaultTools());

        // RAG 回调（带记忆前缀）
        rag.setGenerateFn((systemPrompt, userMsg) -> {
            // RAG callback is shared by all users; request-scoped memory is assembled in processInternal.
            return llm.chat(systemPrompt, List.of(Map.of("role", "user", "content", userMsg)));
        });
        rag.setEmbedFn(text -> llm.embed(text));

        infra.initRAGInfra(cfg.getRag().getRagMilvusDim());

        if (cfg.getRag().getRewrite().isEnabled()) {
            rag.setRewriter(new com.jianjin.assistant.domain.rag.LLMRewriter(
                    (sp, um) -> llm.chat(sp, List.of(Map.of("role", "user", "content", um))),
                    cfg.getRag().getRewrite().getNumQueries()));
        }
        if (cfg.getRag().getRerank().isEnabled()) {
            rag.setReranker(new com.jianjin.assistant.domain.rag.LLMReranker(
                    (sp, um) -> llm.chat(sp, List.of(Map.of("role", "user", "content", um))),
                    cfg.getRag().getRerank().getPreviewLen()));
        }

        // rag_search 工具
        tools.put("rag_search", new Tool("rag_search", "从私人黑洞（个人知识库）中检索相关文档内容",
                List.of(new ToolParam("query", "string", "检索关键词或问题", true)),
                params -> {
                    String q = params.get("query") != null ? params.get("query").toString() : "相关内容";
                    if (!rag.isLoaded()) throw new RuntimeException("知识库为空，请先在「私人黑洞」上传文档");
                    return rag.query(q).answer;
                }));

        // search_web 工具（Tavily + LLM fallback）
        tools.put("search_web", new Tool("search_web", "搜索互联网获取最新信息",
                List.of(new ToolParam("query", "string", "搜索关键词", true)),
                params -> {
                    String q = params.get("query") != null ? params.get("query").toString() : "";
                    if (q.isEmpty()) throw new RuntimeException("搜索关键词不能为空");
                    if (cfg.getSearch().getApiKey() != null && !cfg.getSearch().getApiKey().isEmpty()) {
                        try {
                            return TavilyClient.search(q, cfg.getSearch().getApiKey(), cfg.getSearch().getApiUrl());
                        } catch (Exception ignored) {}
                    }
                    return llm.chat(
                            "你是一个知识丰富的搜索引擎助手。请基于你的知识，对用户的搜索问题给出准确、详细的回答。直接给出答案，不要说「我不知道」或「我无法搜索」。",
                            List.of(Map.of("role", "user", "content", "搜索：" + q)));
                }));

        restoreRAGFromDB();
        initKnowledgeGraph();

        // ===== application/chat 协作者装配 =====
        memoryWriter = new MemoryWriter(cfg, llm, null, null, null, infra);
        snapshotManager = new SnapshotManager(infra);
        subAgents = new com.jianjin.assistant.application.chat.subagent.SubAgentRegistry();
        com.jianjin.assistant.application.chat.subagent.BuiltinSubAgents.registerInto(
                subAgents, cfg, llm, rag, this, library);
        planner = new Planner(cfg, llm, subAgents);
        generator = new ChatGenerator(cfg, llm);
        reactLoop = new ReActLoop(cfg, llm, planner, generator, snapshotManager, subAgents);

        initSandbox();

        log.info("UnifiedAgent 初始化完成: tools={}, STM={}, LTM={}, Prefs={}, KG={}, Sandbox={}",
                tools.size(), 0, 0, 0,
                kg != null && kg.available() ? "ready" : "off",
                sandbox != null ? sandbox.backend() : "off");
    }

    @PreDestroy
    public void shutdown() {
        if (kg != null) kg.close();
    }

    private void initKnowledgeGraph() {
        kg = new KGStore(cfg, (sp, um) -> llm.chat(sp, List.of(Map.of("role", "user", "content", um))));
        rag.setKGStore(kg);

        memorySpaces.setKnowledgeGraph(kg);

        if (kg.available()) {
            log.info("知识图谱已就绪 (Neo4j)，RAG 升级为三路混合检索，记忆系统接入图层");
        } else {
            log.info("Neo4j 不可用，RAG 保持双路检索，记忆系统退化为纯向量模式");
        }
    }

    private void initSandbox() {
        if (!cfg.getSandbox().isEnabled()) {
            log.info("沙箱未启用 (sandbox.enabled=false)，跳过 exec_command 工具");
            return;
        }
        sandbox = SandboxFactory.build(cfg.getSandbox().getBackend(), cfg.getSandbox(), cfg.getSecurity());
        sandbox.setAuditFn(this::auditSandboxResult);
        tools.put("exec_command", ExecCommandTool.create(sandbox));
        log.info("沙箱已就绪，后端={}，exec_command 已注册", sandbox.backend());
    }

    private void auditSandboxResult(ExecResult r) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("command", r.getCommand());
            if (r.getValidation() != null) {
                event.put("level", r.getValidation().getLevel().value());
                event.put("reason", r.getValidation().getReason());
                event.put("violations", r.getValidation().getViolations());
            }
            event.put("exit_code", r.getExitCode());
            event.put("duration_ms", r.getDurationMs());
            event.put("backend", r.getBackend());
            event.put("killed", r.isKilled());
            event.put("truncated", r.isTruncated());
            infra.publishEvent("sandbox.exec", mapper.writeValueAsString(event));
        } catch (Exception ignored) {}
    }

    // ===== Public API =====

    public ChatResponse process(String query) {
        return processWithOptions(query, new ChatRequest());
    }

    public ChatResponse processWithOptions(String query, ChatRequest req) {
        return processInternal(query, req, new AtomicBoolean(false), e -> {});
    }

    public ChatResponse processWithOptions(String query, ChatRequest req, AtomicBoolean cancelled) {
        return processInternal(query, req, cancelled == null ? new AtomicBoolean(false) : cancelled, e -> {});
    }

    public ChatResponse processStream(String query, ChatRequest req, Consumer<StreamEvent> onEvent) {
        return processInternal(query, req, new AtomicBoolean(false), onEvent == null ? e -> {} : onEvent);
    }

    public ChatResponse processStream(String query, ChatRequest req, AtomicBoolean cancelled, Consumer<StreamEvent> onEvent) {
        return processInternal(query, req, cancelled == null ? new AtomicBoolean(false) : cancelled,
                onEvent == null ? e -> {} : onEvent);
    }

    private ChatResponse processInternal(String query, ChatRequest req, AtomicBoolean cancelled, Consumer<StreamEvent> onEvent) {
        MemoryScope scope = MemoryScope.from(req.getUserId(), req.getSessionId());
        UserMemorySpace space = memorySpaces.user(scope.userId());
        SessionMemoryState session = memorySpaces.session(scope);
        session.lock().lock();
        try {
        ShortTermMemory stm = session.messages();
        PreferenceMemory pref = space.preferences();
        LongTermMemory ltm = space.longTerm();
        GraphMemory graphMem = space.graph();
        ChatResponse resp = new ChatResponse();
        resp.setQuery(query);
        resp.setMode("chat");

        onEvent.accept(StreamEvent.start(query));

        sessionMemory.compactBeforeRequest(scope, session, query);
        long userMessageId = infra.saveChatHistory(scope.userId(), scope.sessionId(), "user", query);
        stm.add(userMessageId, "user", query, null);

        // 异步偏好抽取（保留旧行为；MemoryWriter 是回复后才跑的，两者互不冲突）
        runAsyncPreferenceExtraction(scope.userId(), query, pref, ltm, graphMem);

        // 同步规则提取
        String[] extracted = pref.extractAndSave(query);
        if (extracted != null) {
            resp.setExtractedInfo("已记住：" + extracted[0] + " = " + extracted[1]);
        }

        String memPrefix = buildMemorySystemPrefixWithCtx(query, pref, ltm, graphMem);
        String summary = sessionMemory.renderSummary(session.summary());
        if (!summary.isEmpty()) memPrefix = memPrefix.isEmpty() ? summary : summary + "\n\n" + memPrefix;
        List<Map<String, String>> histMsgs = ChatHistoryAdapter.buildHistory(stm, query);

        if (cancelled.get()) {
            resp.setInterrupted(true);
            resp.setAnswer("[已中断] 请求在开始前被取消");
            return resp;
        }

        // 模式决策
        String mode = ChatRouter.decideMode(query, req.isExplicit(), req.isUseRag(),
                req.getSelectedTools(), rag.isLoaded());
        Map<String, Tool> toolset = req.getAllowedToolKeys() == null ? tools : filterTools(req.getAllowedToolKeys());
        if (req.isExplicit() && req.getSelectedTools() != null && !req.getSelectedTools().isEmpty()) {
            Map<String, Tool> filtered = new java.util.HashMap<>();
            for (String name : req.getSelectedTools()) if (toolset.containsKey(name)) filtered.put(name, toolset.get(name));
            if (!filtered.isEmpty()) {
                toolset = filtered;
            } else {
                mode = "chat";
            }
        }

        ensureInputBudget(memPrefix, histMsgs, toolset);

        resp.setMode(mode);
        onEvent.accept(StreamEvent.mode(mode));

        switch (mode) {
            case "react" -> reactLoop.runStream(resp, query, toolset, memPrefix, histMsgs, cancelled, onEvent);
            case "tool" -> new ToolModeHandler(llm, toolService, pref).run(resp, query, toolset, memPrefix, histMsgs);
            case "rag" -> {
                RagService.QueryResult qr = rag.queryWithHistory(scope.userId(), query, toRagHistory(histMsgs, query));
                resp.setAnswer(qr.answer);
                resp.setSearchResults(toSearchResults(qr.results));
                onEvent.accept(StreamEvent.ragResult(resp.getSearchResults()));
            }
            default -> {
                String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
                        "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
                resp.setAnswer(llm.chat(sp, histMsgs));
            }
        }

        if (cancelled.get()) resp.setInterrupted(true);

        long answerMessageId = infra.saveChatHistory(scope.userId(), scope.sessionId(), "assistant", resp.getAnswer());
        stm.add(answerMessageId, "assistant", resp.getAnswer(), null);
        sessionMemory.scheduleSoftCompaction(scope, session);

        // 异步：LLM 分类记忆写入（替代旧的 extractMemoryFromReply）
        memoryWriter.writeAfterReply(scope.userId(), query, resp.getAnswer(), pref, ltm, graphMem);

        // 异步合并：有图层时使用图感知合并
        new Thread(() -> {
            if (graphMem != null && graphMem.needConsolidation()) {
                LongTermMemory.ConsolidationResult result = graphMem.graphAwareConsolidate();
                syncConsolidationToDB(scope.userId(), result);
            } else if (ltm.needConsolidation()) {
                LongTermMemory.ConsolidationResult result = ltm.consolidate();
                syncConsolidationToDB(scope.userId(), result);
            }
        }).start();

        try {
            String eventData = mapper.writeValueAsString(Map.of("query", query, "mode", resp.getMode()));
            infra.publishEvent("agent.chat", eventData);
        } catch (Exception ignored) {}

        resp.setShortTermCount(stm.size());
        resp.setLongTermCount(ltm.size());
        resp.setPreferences(pref.getData());

        onEvent.accept(StreamEvent.done(resp));
        return resp;
        } finally {
            session.lock().unlock();
        }
    }

    public void registerTool(Tool tool) { tools.put(tool.getName(), tool); }

    public void registerTool(String registryName, Tool tool) { tools.put(registryName, tool); }

    public Tool removeTool(String name) { return tools.remove(name); }
    public void removeSession(String userId, String sessionId) { memorySpaces.removeSession(MemoryScope.from(userId, sessionId)); }

    // ===== Accessors =====
    public Map<String, Tool> getTools() { return tools; }
    public ShortTermMemory getShortTermMemory() { return memorySpaces.session(MemoryScope.from(null, null)).messages(); }
    public ShortTermMemory getShortTermMemory(String userId, String sessionId) { return memorySpaces.session(MemoryScope.from(userId, sessionId)).messages(); }
    public LongTermMemory getLongTermMemory() { return memorySpaces.user(MemoryScope.DEFAULT).longTerm(); }
    public LongTermMemory getLongTermMemory(String userId) { return memorySpaces.user(MemoryScope.from(userId, null).userId()).longTerm(); }
    public PreferenceMemory getPreferences() { return memorySpaces.user(MemoryScope.DEFAULT).preferences(); }
    public PreferenceMemory getPreferences(String userId) { return memorySpaces.user(MemoryScope.from(userId, null).userId()).preferences(); }
    public List<Snapshot> getSnapshots() {
        return snapshotManager == null ? new ArrayList<>() : snapshotManager.snapshots();
    }
    public RagService getRagService() { return rag; }
    public KGStore getKnowledgeGraph() { return kg; }
    public Sandbox getSandbox() { return sandbox; }

    // ===== Helpers =====

    private void runAsyncPreferenceExtraction(String userId, String query, PreferenceMemory pref,
                                              LongTermMemory ltm, GraphMemory graphMem) {
        new Thread(() -> {
            Map<String, String> kvs = llm.extractPreferences(query);
            if (kvs == null || kvs.isEmpty()) return;
            pref.saveBatch(kvs);
            for (Map.Entry<String, String> e : kvs.entrySet()) {
                infra.savePreference(userId, e.getKey(), e.getValue());
                String content = "用户" + e.getKey() + ": " + e.getValue();
                List<Double> emb = llm.embed(content);
                boolean added = storeMemory(content, 0.8, emb, ltm, graphMem);
                if (added) {
                    String embJson = "null";
                    try { if (emb != null) embJson = mapper.writeValueAsString(emb); } catch (Exception ignored) {}
                    int pgId = infra.saveLongTermItem(userId, content, 0.8, embJson);
                    syncMemoryPGID(pgId, ltm, graphMem);
                }
            }
        }, "preference-extract").start();
    }

    private boolean storeMemory(String content, double importance, List<Double> emb, LongTermMemory ltm, GraphMemory graphMem) {
        if (graphMem != null) return graphMem.store(content, importance, emb).added();
        return ltm.store(content, importance, emb);
    }

    private void syncMemoryPGID(int pgId, LongTermMemory ltm, GraphMemory graphMem) {
        if (graphMem != null) graphMem.syncLastItemPGID(pgId);
        else ltm.syncLastItemPGID(pgId);
    }

    private String buildMemorySystemPrefix() {
        UserMemorySpace defaultSpace = memorySpaces.user(MemoryScope.DEFAULT);
        PreferenceMemory pref = defaultSpace.preferences();
        LongTermMemory ltm = defaultSpace.longTerm();
        List<String> parts = new ArrayList<>();
        String prefCtx = pref.buildContext();
        if (!prefCtx.isEmpty()) parts.add(prefCtx);
        List<MemoryItem> ltmItems = ltm.getItems();
        if (!ltmItems.isEmpty()) {
            List<String> contents = ltmItems.stream().map(MemoryItem::getContent).toList();
            parts.add("【长期记忆】\n" + String.join("\n", contents));
        }
        return String.join("\n\n", parts);
    }

    private void ensureInputBudget(String memPrefix, List<Map<String, String>> history, Map<String, Tool> toolset) {
        int tokens = com.jianjin.assistant.service.memory.TokenEstimator.estimate(memPrefix) + 160;
        if (history != null) {
            for (Map<String, String> message : history) {
                tokens += com.jianjin.assistant.service.memory.TokenEstimator.estimateMessage(
                        message.getOrDefault("role", "user"), message.getOrDefault("content", ""));
            }
        }
        if (toolset != null) {
            for (Tool tool : toolset.values()) {
                tokens += com.jianjin.assistant.service.memory.TokenEstimator.estimate(tool.getName() + " " + tool.getDescription()) + 32;
            }
        }
        int limit = cfg.getMemory().getContextWindowTokens() - cfg.getMemory().getReservedOutputTokens();
        if (tokens > limit) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "系统上下文超过 Token 预算");
        }
    }

    private String buildMemorySystemPrefixWithCtx(String query, PreferenceMemory pref,
                                                   LongTermMemory ltm, GraphMemory graphMem) {
        List<String> parts = new ArrayList<>();
        String prefCtx = pref.buildContext();
        if (!prefCtx.isEmpty()) parts.add(prefCtx);

        List<Double> queryEmb = llm.embed(query);
        List<MemoryItem> recalled = (graphMem != null
                ? graphMem.recall(query, cfg.getMemory().getLongTermTopK(), queryEmb)
                : ltm.recall(query, cfg.getMemory().getLongTermTopK(), queryEmb));
        if (!recalled.isEmpty()) {
            List<String> contents = recalled.stream().map(MemoryItem::getContent).toList();
            parts.add("【相关记忆】\n" + String.join("\n", contents));
        }
        return String.join("\n\n", parts);
    }

    private Map<String, Tool> filterTools(List<String> names) {
        Map<String, Tool> result = new java.util.HashMap<>();
        for (String name : names) {
            if (tools.containsKey(name)) result.put(name, tools.get(name));
        }
        return result;
    }

    private void syncConsolidationToDB(String userId, LongTermMemory.ConsolidationResult result) {
        if (!result.deleteFromDB.isEmpty()) {
            infra.deleteLongTermItems(userId, result.deleteFromDB);
            log.info("记忆合并：删除 {} 条（去重={}, 合并={}, 过期={}）",
                    result.deduped + result.merged + result.expired,
                    result.deduped, result.merged, result.expired);
        }
        for (MemoryItem item : result.updateInDB) {
            String embJson = "null";
            try { if (item.getEmbedding() != null) embJson = mapper.writeValueAsString(item.getEmbedding()); } catch (Exception ignored) {}
            infra.updateLongTermItem(userId, item.getId(), item.getContent(), item.getImportance(), embJson);
        }
    }

    private void restoreRAGFromDB() {
        List<InfrastructureService.ChunkRow> chunkRows = infra.loadAllRAGChunks();
        if (chunkRows.isEmpty()) return;
        List<com.jianjin.assistant.model.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < chunkRows.size(); i++) {
            chunks.add(new com.jianjin.assistant.model.Chunk(i, chunkRows.get(i).content));
        }
        rag.restoreChunks(chunks);
        log.info("RAG chunks 恢复：{} 条", chunks.size());
    }

    private List<ChatResponse.SearchResultDto> toSearchResults(List<RagService.ScoredChunk> results) {
        if (results == null) return null;
        return results.stream()
                .map(r -> new ChatResponse.SearchResultDto(r.chunk, r.score))
                .toList();
    }

    /** Converts chat messages to rewrite history and excludes the current user query. */
    private List<HistoryMessage> toRagHistory(List<Map<String, String>> messages, String currentQuery) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<HistoryMessage> history = new ArrayList<>();
        int last = messages.size() - 1;
        for (int i = 0; i < messages.size(); i++) {
            Map<String, String> message = messages.get(i);
            if (message == null) continue;
            String role = message.getOrDefault("role", "user");
            String content = message.getOrDefault("content", "");
            if (i == last && "user".equals(role) && content.equals(currentQuery)) continue;
            history.add(new HistoryMessage(role, content));
        }
        return history;
    }

    /** 兼容签名保留（旧调用点已统一改用 TavilyClient.search）。 */
    static String tavilySearch(String query, String apiKey, String apiUrl) throws Exception {
        return TavilyClient.search(query, apiKey, apiUrl);
    }
}
