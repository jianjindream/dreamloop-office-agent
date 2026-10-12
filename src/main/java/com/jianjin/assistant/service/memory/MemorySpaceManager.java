package com.jianjin.assistant.service.memory;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.model.MemoryItem;
import com.jianjin.assistant.service.graph.KGStore;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

@Component
public class MemorySpaceManager {
    private final AppConfig cfg;
    private final InfrastructureService infra;
    private final Executor executor;
    private final ConcurrentMap<String, UserMemorySpace> users = new ConcurrentHashMap<>();
    private final ConcurrentMap<MemoryScope, SessionMemoryState> sessions = new ConcurrentHashMap<>();
    private volatile KGStore kg;

    public MemorySpaceManager(AppConfig cfg, InfrastructureService infra) {
        this(cfg, infra, Runnable::run);
    }

    @Autowired
    public MemorySpaceManager(AppConfig cfg, InfrastructureService infra, MemoryTaskExecutor executor) {
        this(cfg, infra, (Executor) executor);
    }

    private MemorySpaceManager(AppConfig cfg, InfrastructureService infra, Executor executor) {
        this.cfg = cfg; this.infra = infra; this.executor = executor;
    }

    public UserMemorySpace user(String userId) {
        return users.computeIfAbsent(userId, this::loadUser);
    }

    public SessionMemoryState session(MemoryScope scope) {
        return sessions.computeIfAbsent(scope, this::loadSession);
    }

    public void removeSession(MemoryScope scope) { sessions.remove(scope); }

    public void setKnowledgeGraph(KGStore graphStore) {
        this.kg = graphStore;
        users.forEach((id, space) -> {
            GraphMemory graph = new GraphMemory(id, space.longTerm(), graphStore,
                    cfg.getMemory().getConsolidation().getSimilarityThreshold(), executor);
            graph.syncPrevId();
            space.setGraph(graph);
        });
    }

    private UserMemorySpace loadUser(String userId) {
        PreferenceMemory pref = new PreferenceMemory();
        pref.saveBatch(infra.loadPreferences(userId));
        LongTermMemory ltm = new LongTermMemory();
        ltm.setConsolidationConfig(cfg.getMemory().getConsolidation());
        for (InfrastructureService.LongTermRow row : infra.loadLongTermItems(userId)) {
            MemoryItem item = new MemoryItem();
            item.setId(row.id); item.setContent(row.content); item.setImportance(row.importance); item.setEmbedding(row.embedding);
            if (row.createdAt != null) item.setCreatedAt(row.createdAt.toLocalDateTime());
            if (row.lastAccessed != null) item.setLastAccessed(row.lastAccessed.toLocalDateTime());
            item.setCategory(row.category); item.setTags(row.tags); item.setSlotHint(row.slotHint);
            ltm.storeItem(item);
        }
        UserMemorySpace space = new UserMemorySpace(pref, ltm);
        KGStore graphStore = kg;
        if (graphStore != null) {
            GraphMemory graph = new GraphMemory(userId, ltm, graphStore,
                    cfg.getMemory().getConsolidation().getSimilarityThreshold(), executor);
            graph.syncPrevId();
            space.setGraph(graph);
        }
        return space;
    }

    private SessionMemoryState loadSession(MemoryScope scope) {
        SessionSummary summary = infra.loadSessionSummary(scope.userId(), scope.sessionId());
        ShortTermMemory stm = new ShortTermMemory();
        // Compaction, rather than the raw window, decides when old messages leave this session.
        stm.setMaxTurns(10_000);
        for (InfrastructureService.ChatHistoryRow row : infra.loadChatHistory(scope.userId(), scope.sessionId(),
                summary.summarizedThroughId, 10_000)) {
            stm.add(row.id, row.role, row.content, row.createdAt);
        }
        return new SessionMemoryState(stm, summary);
    }
}
