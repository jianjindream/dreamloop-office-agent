package com.jianjin.assistant.interfaces.http.controller;

import com.jianjin.assistant.application.chat.ChatApplicationService;
import com.jianjin.assistant.application.chat.ChatTaskRegistry;
import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.domain.document.Document;
import com.jianjin.assistant.domain.document.ParseResult;
import com.jianjin.assistant.domain.document.WriteRequest;
import com.jianjin.assistant.domain.document.LibraryRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.dto.ChatRequest;
import com.jianjin.assistant.dto.ChatResponse;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.model.Chunk;
import com.jianjin.assistant.model.Snapshot;
import com.jianjin.assistant.model.Tool;
import com.jianjin.assistant.model.ToolParam;
import com.jianjin.assistant.service.agent.UnifiedAgentService;
import com.jianjin.assistant.service.document.DocumentLibraryService;
import com.jianjin.assistant.service.document.DocumentParser;
import com.jianjin.assistant.service.document.UploadProgressService;
import com.jianjin.assistant.interfaces.http.security.RequestIdentity;
import com.jianjin.assistant.interfaces.http.security.RequestIdentityHolder;
import com.jianjin.assistant.interfaces.http.error.ApiException;
import com.jianjin.assistant.service.rag.RagService;
import com.jianjin.assistant.service.tools.ToolService;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Instant;
import java.util.UUID;
import java.net.URI;
import java.net.InetAddress;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private static final ObjectMapper SSE_MAPPER = new ObjectMapper();

    private final ChatApplicationService chat;
    private final UnifiedAgentService agent;
    private final InfrastructureService infra;
    private final AppConfig cfg;
    private final ToolService toolService;
    private final DocumentParser parser;
    private final DocumentLibraryService library;
    private final ChatTaskRegistry tasks;
    private final UploadProgressService uploadProgress;

    public AgentController(ChatApplicationService chat,
                           UnifiedAgentService agent,
                           InfrastructureService infra,
                           AppConfig cfg,
                           ToolService toolService,
                           DocumentParser parser,
                           DocumentLibraryService library,
                           ChatTaskRegistry tasks,
                           UploadProgressService uploadProgress) {
        this.chat = chat;
        this.agent = agent;
        this.infra = infra;
        this.cfg = cfg;
        this.toolService = toolService;
        this.parser = parser;
        this.library = library;
        this.tasks = tasks;
        this.uploadProgress = uploadProgress;
    }

    /** POST /api/chat — 统一对话入口（同步模式，向后兼容） */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        prepareChatRequest(req);
        RequestIdentity identity = RequestIdentityHolder.current();
        ChatTaskRegistry.TaskHandle task = tasks.create(identity.ownerKey());
        try {
            ChatResponse response = chat.process(req, task.cancelled());
            response.setTaskId(task.taskId());
            return response;
        } finally {
            tasks.complete(task.taskId());
        }
    }

    /** POST /api/chat/stream — SSE 流式对话入口（真流式：逐 step 推送事件） */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest req) {
        prepareChatRequest(req);
        RequestIdentity identity = RequestIdentityHolder.current();
        ChatTaskRegistry.TaskHandle task = tasks.create(identity.ownerKey());
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicLong sequence = new AtomicLong();
        emitter.onTimeout(() -> tasks.cancel(task.taskId(), identity.ownerKey()));
        executor.submit(() -> {
            try {
                chat.processStream(req, task.cancelled(), ev -> {
                    try {
                        emitter.send(SseEmitter.event().id(task.taskId() + ":" + sequence.incrementAndGet())
                                .name(ev.type()).data(streamPayload(task.taskId(), sequence.get(), ev.type(), ev.data())));
                    } catch (Exception ignored) {
                        task.cancelled().set(true);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(streamPayload(task.taskId(), sequence.incrementAndGet(),
                            "error", Map.of("message", e.getMessage() == null ? "生成失败" : e.getMessage(), "code", "CHAT_STREAM_ERROR"))));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            } finally {
                tasks.complete(task.taskId());
            }
        });
        return emitter;
    }

    /** POST /api/chat/tasks/{taskId}/cancel — only cancels the caller's generation. */
    @PostMapping("/chat/tasks/{taskId}/cancel")
    public Map<String, Object> chatCancel(@PathVariable String taskId) {
        boolean cancelled = tasks.cancel(taskId, RequestIdentityHolder.current().ownerKey());
        if (!cancelled) throw new ApiException("TASK_NOT_FOUND", "任务不存在或无权取消", HttpStatus.NOT_FOUND);
        return Map.of("ok", true, "task_id", taskId, "message", "已发送取消信号");
    }

    /** POST /api/upload — 上传文档到 RAG 知识库（JSON: content 字符串） */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestBody Map<String, String> req) {
        String content = req.get("content");
        if (content == null || content.isEmpty()) throw new ApiException("CONTENT_REQUIRED", "content is required", HttpStatus.BAD_REQUEST);
        Map.Entry<Integer, String> result = agent.getRagService().ingest(RequestIdentityHolder.current().ownerKey(), content);
        return Map.of(
                "chunk_count", result.getKey(),
                "doc_hash", result.getValue(),
                "chunks", agent.getRagService().getChunks()
        );
    }

    /**
     * POST /api/upload/file — multipart 上传（支持 PDF / txt / md）。
     *
     * <p>路径：{@link DocumentParser} 解析 → 写入 {@link DocumentLibraryService}（来源 user_upload）→
     * 同步 ingest 到 RAG。返回解析摘要 + RAG ingest 结果 + 创建出的文档 id。</p>
     */
    @PostMapping(value = "/upload/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "upload_id", required = false) String uploadId) {
        RequestIdentity identity = RequestIdentityHolder.current();
        if (uploadId == null || uploadId.isBlank()) uploadId = "upload_" + UUID.randomUUID().toString().replace("-", "");
        uploadProgress.update(identity.ownerKey(), uploadId, 5, "received", "文件已接收");
        if (file == null || file.isEmpty()) throw new ApiException("FILE_REQUIRED", "file is required", HttpStatus.BAD_REQUEST);
        byte[] data;
        try {
            uploadProgress.update(identity.ownerKey(), uploadId, 20, "reading", "正在读取文件");
            data = file.getBytes();
        } catch (Exception e) {
            uploadProgress.update(identity.ownerKey(), uploadId, 0, "failed", "读取文件失败");
            throw new ApiException("FILE_READ_FAILED", "failed to read file: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
        ParseResult pr;
        try {
            uploadProgress.update(identity.ownerKey(), uploadId, 40, "parsing", "正在解析文件内容");
            pr = parser.parseBytes(file.getOriginalFilename(),
                    file.getContentType(), data);
        } catch (DocumentParser.PdfNeedsOcrException e) {
            ParseResult r = e.getResult();
            uploadProgress.update(identity.ownerKey(), uploadId, 0, "failed", "PDF 需要 OCR");
            throw new ApiException("PDF_NEEDS_OCR", e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of("needs_ocr", true, "pages", r == null ? 0 : r.pages));
        } catch (Exception e) {
            uploadProgress.update(identity.ownerKey(), uploadId, 0, "failed", "文件解析失败");
            throw new ApiException("FILE_PARSE_FAILED", e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // 落库到本地文档库 + ingest 到 RAG（来源标记为 user_upload）
        String title = file.getOriginalFilename() == null ? pr.parser + " upload" : file.getOriginalFilename();
        WriteRequest req = new WriteRequest(title, "upload", Document.SOURCE_UPLOAD, "user",
                pr.content,
                pr.content.length() > 180 ? pr.content.substring(0, 180) + "..." : pr.content,
                new LinkedHashMap<>(Map.of(
                        "parser", pr.parser, "pages", pr.pages,
                        "text_chars", pr.textChars, "needs_ocr", pr.needsOCR)));
        req.getMetadata().put("owner_key", identity.ownerKey());
        uploadProgress.update(identity.ownerKey(), uploadId, 70, "indexing", "正在写入文档和检索索引");
        DocumentLibraryService.Result res = library.writeDocument(req, true);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filename", pr.filename);
        out.put("content_type", pr.contentType);
        out.put("parser", pr.parser);
        out.put("pages", pr.pages);
        out.put("text_chars", pr.textChars);
        out.put("needs_ocr", pr.needsOCR);
        out.put("document_id", res.document.getId());
        out.put("version_id", res.version.getId());
        out.put("upload_id", uploadId);
        if (res.ingestChunks != null) {
            out.put("chunk_count", res.ingestChunks);
            out.put("doc_hash", res.ingestDocHash);
        }
        uploadProgress.update(identity.ownerKey(), uploadId, 100, "completed", "处理完成");
        return out;
    }

    @GetMapping("/uploads/{uploadId}/progress")
    public UploadProgressService.Progress uploadProgress(@PathVariable String uploadId) {
        return uploadProgress.get(RequestIdentityHolder.current().ownerKey(), uploadId);
    }

    /** GET /api/documents — 文档库列表 */
    @GetMapping("/documents")
    public List<Document> listDocuments() {
        String owner = RequestIdentityHolder.current().ownerKey();
        return library.list().stream().filter(document -> ownsDocument(document.getId(), owner)).toList();
    }

    /** GET /api/documents/{id} — 取单个文档（含最新版本正文） */
    @GetMapping("/documents/{id}")
    public Map<String, Object> getDocument(@PathVariable("id") String id) {
        var dv = ownedDocument(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document", dv.document);
        out.put("version", dv.version);
        return out;
    }

    /** DELETE /api/documents/{id} — delete the document record and every immutable version. */
    @DeleteMapping("/documents/{id}")
    public Map<String, Object> deleteDocument(@PathVariable("id") String id) {
        ownedDocument(id);
        if (!library.delete(id)) throw new ApiException("DOCUMENT_NOT_FOUND", "文档不存在", HttpStatus.NOT_FOUND);
        return Map.of("ok", true, "document_id", id);
    }

    /** GET /api/documents/{id}/versions — 按新到旧查看版本。 */
    @GetMapping("/documents/{id}/versions")
    public List<com.jianjin.assistant.domain.document.DocumentVersion> listDocumentVersions(@PathVariable("id") String id) {
        ownedDocument(id);
        return library.listVersions(id);
    }

    /** POST /api/documents — 由前端直接写入新文档（标题 / 正文 / docType / ingestToRAG） */
    @PostMapping("/documents")
    public Map<String, Object> writeDocument(@RequestBody Map<String, Object> req) {
        String title = (String) req.getOrDefault("title", "");
        String content = (String) req.getOrDefault("content_md", "");
        String docType = (String) req.getOrDefault("doc_type", "note");
        boolean ingest = Boolean.TRUE.equals(req.get("ingest_to_rag"));
        WriteRequest wr = new WriteRequest(title, docType, Document.SOURCE_UPLOAD, RequestIdentityHolder.current().ownerKey(),
                content, "", documentMetadata(req));
        DocumentLibraryService.Result res = library.writeDocument(wr, ingest);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document", res.document);
        out.put("version", res.version);
        out.put("created", res.created);
        if (res.ingestChunks != null) {
            out.put("chunk_count", res.ingestChunks);
            out.put("doc_hash", res.ingestDocHash);
        }
        return out;
    }

    /** PUT /api/documents/{id} — 更新正文或标题，并创建不可变的新版本。 */
    @PutMapping("/documents/{id}")
    public Map<String, Object> updateDocument(@PathVariable("id") String id, @RequestBody Map<String, Object> req) {
        var current = ownedDocument(id);
        String title = (String) req.getOrDefault("title", current.document.getTitle());
        String content = (String) req.getOrDefault("content_md", current.version.getContentMd());
        String docType = (String) req.getOrDefault("doc_type", current.document.getDocType());
        Map<String, Object> metadata = new LinkedHashMap<>(current.version.getMetadata());
        metadata.putAll(documentMetadata(req));
        metadata.put("owner_key", RequestIdentityHolder.current().ownerKey());
        WriteRequest wr = new WriteRequest(title, docType, current.document.getSource(),
                current.document.getCreatedBy(), content, current.version.getSummary(), metadata);
        wr.setDocumentId(id);
        DocumentLibraryService.Result res = library.writeDocument(wr, Boolean.TRUE.equals(req.get("ingest_to_rag")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document", res.document);
        out.put("version", res.version);
        out.put("created", false);
        return out;
    }

    private Map<String, Object> documentMetadata(Map<String, Object> req) {
        Object value = req.get("metadata");
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            raw.forEach((key, item) -> {
                if (key instanceof String && !"owner_key".equals(key)) metadata.put((String) key, item);
            });
        }
        metadata.put("owner_key", RequestIdentityHolder.current().ownerKey());
        return metadata;
    }

    /** POST /api/docs/delete — 删除指定文档的所有 chunks */
    @PostMapping("/docs/delete")
    public Map<String, Object> docsDelete(@RequestBody Map<String, String> req) {
        String docHash = req.get("doc_hash");
        if (docHash == null || docHash.isEmpty()) throw new ApiException("DOC_HASH_REQUIRED", "doc_hash is required", HttpStatus.BAD_REQUEST);
        agent.getRagService().delete(RequestIdentityHolder.current().ownerKey(), docHash);
        return Map.of("ok", true, "doc_hash", docHash);
    }

    /** GET /api/memory — 查看指定用户/会话的记忆状态；未传参数兼容默认空间。 */
    @GetMapping("/memory")
    public Map<String, Object> memory(@RequestParam(value = "user_id", required = false) String userId,
                                      @RequestParam(value = "session_id", required = false) String sessionId) {
        String owner = RequestIdentityHolder.current().ownerKey();
        return Map.of(
                "short_term", agent.getShortTermMemory(owner, sessionId).getMessages(),
                "long_term", agent.getLongTermMemory(owner).getItems(),
                "preference", agent.getPreferences(owner).getData()
        );
    }

    /** GET /api/tools — 列出所有可用工具 */
    @GetMapping("/tools")
    public List<Map<String, Object>> toolsList() {
        List<Map<String, Object>> list = new ArrayList<>();
        String prefix = mcpPrefix();
        for (Map.Entry<String, Tool> entry : agent.getTools().entrySet()) {
            Tool t = entry.getValue();
            if (t.isMcp() && !entry.getKey().startsWith(prefix)) continue;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", t.getName());
            info.put("description", t.getDescription());
            if (t.isMcp()) info.put("is_mcp", true);
            if (t.isMcp()) info.put("endpoint", t.getEndpoint());
            if (t.getParameters() != null && !t.getParameters().isEmpty()) {
                info.put("params", t.getParameters());
            }
            list.add(info);
        }
        return list;
    }

    /** POST /api/tools/mcp — 动态注册一个 MCP 工具 */
    @PostMapping("/tools/mcp")
    public Map<String, Object> registerMCPTool(@RequestBody Map<String, Object> req) {
        String name = (String) req.get("name");
        String description = (String) req.get("description");
        String endpoint = (String) req.get("endpoint");
        validateMcp(name, endpoint);
        Tool tool = toolService.createMCPTool(name, description != null ? description : "", endpoint, toolParams(req));
        agent.registerTool(mcpKey(name), tool);
        return Map.of("ok", true, "name", name);
    }

    /** PUT /api/tools/mcp/{name} — replace a workspace-owned MCP tool definition. */
    @PutMapping("/tools/mcp/{name}")
    public Map<String, Object> updateMCPTool(@PathVariable String name, @RequestBody Map<String, Object> req) {
        String endpoint = (String) req.get("endpoint");
        validateMcp(name, endpoint);
        String key = mcpKey(name);
        if (!agent.getTools().containsKey(key)) throw new ApiException("TOOL_NOT_FOUND", "MCP 工具不存在", HttpStatus.NOT_FOUND);
        Tool tool = toolService.createMCPTool(name, String.valueOf(req.getOrDefault("description", "")), endpoint, toolParams(req));
        agent.registerTool(key, tool);
        return Map.of("ok", true, "name", name);
    }

    /** DELETE /api/tools/mcp/{name} — remove a workspace-owned MCP tool. */
    @DeleteMapping("/tools/mcp/{name}")
    public Map<String, Object> deleteMCPTool(@PathVariable String name) {
        Tool removed = agent.removeTool(mcpKey(name));
        if (removed == null || !removed.isMcp()) throw new ApiException("TOOL_NOT_FOUND", "MCP 工具不存在", HttpStatus.NOT_FOUND);
        return Map.of("ok", true, "name", name);
    }

    /** GET /api/sessions — server-side conversation list scoped to the authenticated workspace user. */
    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : infra.listChatSessions(RequestIdentityHolder.current().ownerKey())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.sessionId); item.put("title", row.title); item.put("created_at", row.createdAt);
            item.put("updated_at", row.updatedAt); item.put("message_count", row.messageCount); out.add(item);
        }
        return out;
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<Map<String, Object>> sessionMessages(@PathVariable String sessionId,
            @RequestParam(value="after_id", defaultValue="0") long afterId,
            @RequestParam(value="limit", defaultValue="200") int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : infra.loadChatHistory(RequestIdentityHolder.current().ownerKey(), sessionId, afterId, safeLimit)) {
            out.add(Map.of("id", row.id, "role", row.role, "content", row.content, "created_at", row.createdAt));
        }
        return out;
    }

    @PatchMapping("/sessions/{sessionId}")
    public Map<String, Object> renameSession(@PathVariable String sessionId, @RequestBody Map<String,Object> request) {
        Object requestedTitle = request.get("title");
        if (!(requestedTitle instanceof String title) || title.isBlank()) {
            throw new ApiException("SESSION_TITLE_REQUIRED", "title is required", HttpStatus.BAD_REQUEST);
        }
        boolean updated = infra.renameChatSession(RequestIdentityHolder.current().ownerKey(), sessionId, title);
        if (!updated) throw new ApiException("SESSION_NOT_FOUND", "会话不存在", HttpStatus.NOT_FOUND);
        return Map.of("ok", true, "session_id", sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        String owner = RequestIdentityHolder.current().ownerKey();
        if (!infra.deleteChatSession(owner, sessionId)) {
            throw new ApiException("SESSION_NOT_FOUND", "会话不存在", HttpStatus.NOT_FOUND);
        }
        agent.removeSession(owner, sessionId);
        return Map.of("ok", true, "session_id", sessionId);
    }

    @GetMapping("/auth/me")
    public Map<String,Object> currentUser() {
        RequestIdentity identity = RequestIdentityHolder.current();
        return Map.of("user_id", identity.userId(), "workspace_id", identity.workspaceId());
    }

    /** GET /api/snapshots — 列出任务执行快照摘要 */
    @GetMapping("/snapshots")
    public List<Map<String, Object>> snapshots() {
        List<Snapshot> snaps = agent.getSnapshots();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < snaps.size(); i++) {
            Snapshot snap = snaps.get(i);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("index", i);
            info.put("timestamp", snap.getTimestamp());
            info.put("steps", snap.getState().getSteps() != null ? snap.getState().getSteps().size() : 0);
            result.add(info);
        }
        return result;
    }

    /** GET /api/status — 系统状态与配置摘要 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        String owner = RequestIdentityHolder.current().ownerKey();
        RagService ragService = agent.getRagService();
        List<Chunk> chunks = ragService.getChunks();
        List<Map<String, Object>> chunkPreviews = new ArrayList<>();
        for (Chunk c : chunks) {
            String preview = c.getContent();
            if (preview != null && preview.length() > 60) preview = preview.substring(0, 60) + "...";
            chunkPreviews.add(Map.of("id", c.getId(), "content", preview));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rag_loaded", ragService.isLoaded());
        result.put("rag_mode", ragService.getMode());
        result.put("rag_chunks", chunkPreviews);
        result.put("short_term_count", agent.getShortTermMemory(owner, null).size());
        result.put("long_term_count", agent.getLongTermMemory(owner).size());
        result.put("preferences", agent.getPreferences(owner).getData());
        result.put("tools_count", toolsList().size());
        result.put("llm_model", cfg.getLlm().getModel());
        result.put("embedding_model", cfg.getEmbedding().getModel());
        result.put("is_mock", !cfg.isRealLLM());
        result.put("infrastructure", infra.getStatus());
        return result;
    }

    private void prepareChatRequest(ChatRequest req) {
        if (req == null || req.getMessage() == null || req.getMessage().isBlank()) {
            throw new ApiException("CHAT_MESSAGE_REQUIRED", "message is required", HttpStatus.BAD_REQUEST);
        }
        req.setUserId(RequestIdentityHolder.current().ownerKey());
        String sessionId = req.getSessionId();
        if (sessionId == null || sessionId.isBlank()) sessionId = "session_" + UUID.randomUUID().toString().replace("-", "");
        if (!sessionId.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new ApiException("INVALID_SESSION_ID", "session_id 格式不正确", HttpStatus.BAD_REQUEST);
        }
        req.setSessionId(sessionId);
        if (req.getSelectedTools() != null) {
            req.setSelectedTools(req.getSelectedTools().stream().map(name ->
                    agent.getTools().containsKey(mcpKey(name)) ? mcpKey(name) : name).toList());
        }
        String prefix = mcpPrefix();
        req.setAllowedToolKeys(agent.getTools().entrySet().stream()
                .filter(entry -> !entry.getValue().isMcp() || entry.getKey().startsWith(prefix))
                .map(Map.Entry::getKey).toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> streamPayload(String taskId, long sequence, String type, Object data) {
        Map<String, Object> payload = data instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : SSE_MAPPER.convertValue(data, LinkedHashMap.class);
        payload.put("task_id", taskId);
        payload.put("event_type", type);
        payload.put("sequence", sequence);
        payload.put("emitted_at", Instant.now());
        return payload;
    }

    private LibraryRepo.DocumentWithVersion ownedDocument(String id) {
        LibraryRepo.DocumentWithVersion document;
        try {
            document = library.get(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException("DOCUMENT_NOT_FOUND", "文档不存在", HttpStatus.NOT_FOUND);
        }
        Object owner = document.version.getMetadata().get("owner_key");
        String current = RequestIdentityHolder.current().ownerKey();
        if (owner == null && "default:default".equals(current)) return document;
        if (!current.equals(String.valueOf(owner))) {
            throw new ApiException("DOCUMENT_NOT_FOUND", "文档不存在", HttpStatus.NOT_FOUND);
        }
        return document;
    }

    private boolean ownsDocument(String id, String owner) {
        try {
            Object stored = library.get(id).version.getMetadata().get("owner_key");
            return owner.equals(String.valueOf(stored)) || (stored == null && "default:default".equals(owner));
        } catch (RuntimeException ignored) { return false; }
    }

    @SuppressWarnings("unchecked")
    private List<ToolParam> toolParams(Map<String, Object> request) {
        List<ToolParam> params = new ArrayList<>();
        Object raw = request.get("params");
        if (!(raw instanceof List<?> values)) return params;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> param)) continue;
            Object paramName = param.get("name");
            Object paramType = param.get("type");
            Object paramDescription = param.get("description");
            params.add(new ToolParam(paramName == null ? "" : String.valueOf(paramName),
                    paramType == null ? "string" : String.valueOf(paramType),
                    paramDescription == null ? "" : String.valueOf(paramDescription), Boolean.TRUE.equals(param.get("required"))));
        }
        return params;
    }

    private void validateMcp(String name, String endpoint) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_-]{1,63}")) {
            throw new ApiException("INVALID_TOOL_NAME", "name 格式不正确", HttpStatus.BAD_REQUEST);
        }
        if (endpoint == null || !(endpoint.startsWith("https://") || endpoint.startsWith("http://"))) {
            throw new ApiException("INVALID_TOOL_ENDPOINT", "endpoint 必须是 HTTP(S) 地址", HttpStatus.BAD_REQUEST);
        }
        try {
            URI uri = URI.create(endpoint);
            if (uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            if (!cfg.getSecurity().isAllowPrivateMcpEndpoints()) {
                for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                            || address.isSiteLocalAddress()) {
                        throw new ApiException("PRIVATE_TOOL_ENDPOINT", "MCP endpoint 不允许指向本机或私有网络", HttpStatus.BAD_REQUEST);
                    }
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("INVALID_TOOL_ENDPOINT", "endpoint 地址无效或无法解析", HttpStatus.BAD_REQUEST);
        }
    }

    private String mcpPrefix() { return "workspace:" + RequestIdentityHolder.current().ownerKey() + "::"; }
    private String mcpKey(String name) { return mcpPrefix() + name; }
}
