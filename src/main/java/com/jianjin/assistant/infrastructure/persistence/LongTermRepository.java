package com.jianjin.assistant.infrastructure.persistence;

import com.jianjin.assistant.infrastructure.platform.PostgresConnector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Repository
public class LongTermRepository {

    private static final Logger log = LoggerFactory.getLogger(LongTermRepository.class);
    private static final ObjectMapper M = new ObjectMapper().registerModule(new JavaTimeModule());

    private final PostgresConnector pg;
    private final AtomicInteger memoryIds = new AtomicInteger();
    private final Map<String, List<Row>> memoryData = new ConcurrentHashMap<>();

    public LongTermRepository(PostgresConnector pg) {
        this.pg = pg;
    }

    public static class Row {
        public int id;
        public String content;
        public double importance;
        public List<Double> embedding;
        public Timestamp createdAt;
        public Timestamp lastAccessed;
        public String category;
        public List<String> tags;
        public String slotHint;
        public double baseImportance;
        public int recallCount;
        public Timestamp lastRecalledAt;
    }

    public synchronized int save(String userId, String content, double importance, String embeddingJson) {
        return saveClassified(userId, content, importance, embeddingJson, "general", null, null);
    }

    public synchronized int saveClassified(String userId, String content, double importance, String embeddingJson,
                              String category, String tagsJson, String slotHint) {
        Connection c = pg.connection();
        if (c == null) return saveMemory(userId, content, importance, embeddingJson, category, tagsJson, slotHint);
        String cat = (category == null || category.isEmpty()) ? "general" : category;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO long_term_memory (user_id, content, importance, embedding, category, tags, slot_hint, base_importance) " +
                        "VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?) RETURNING id")) {
            ps.setString(1, userId); ps.setString(2, content);
            ps.setDouble(3, importance); ps.setString(4, embeddingJson);
            ps.setString(5, cat); ps.setString(6, tagsJson); ps.setString(7, slotHint);
            ps.setDouble(8, importance);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.warn("长期记忆保存失败: {}", e.getMessage());
        }
        return -1;
    }

    public synchronized List<Row> loadAll(String userId) {
        List<Row> items = new ArrayList<>();
        Connection c = pg.connection();
        if (c == null) {
            for (Row row : memoryData.getOrDefault(userId, List.of())) items.add(copy(row));
            return items;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, content, importance, embedding, " +
                        "COALESCE(created_at, NOW()), COALESCE(last_accessed, NOW()), " +
                        "COALESCE(category, 'general'), tags, slot_hint, " +
                        "COALESCE(base_importance, importance), COALESCE(recall_count, 0), last_recalled_at " +
                        "FROM long_term_memory WHERE user_id = ? ORDER BY id")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Row row = new Row();
                row.id = rs.getInt(1); row.content = rs.getString(2); row.importance = rs.getDouble(3);
                String embJson = rs.getString(4);
                if (embJson != null && !embJson.isEmpty()) {
                    try {
                        row.embedding = M.readValue(embJson,
                                M.getTypeFactory().constructCollectionType(List.class, Double.class));
                    } catch (Exception ignored) {}
                }
                row.createdAt = rs.getTimestamp(5);
                row.lastAccessed = rs.getTimestamp(6);
                row.category = rs.getString(7);
                String tagsJson = rs.getString(8);
                if (tagsJson != null && !tagsJson.isEmpty()) {
                    try {
                        row.tags = M.readValue(tagsJson,
                                M.getTypeFactory().constructCollectionType(List.class, String.class));
                    } catch (Exception ignored) {}
                }
                row.slotHint = rs.getString(9);
                row.baseImportance = rs.getDouble(10);
                row.recallCount = rs.getInt(11);
                row.lastRecalledAt = rs.getTimestamp(12);
                items.add(row);
            }
            }
        } catch (SQLException e) {
            log.warn("加载长期记忆失败: {}", e.getMessage());
        }
        return items;
    }

    public synchronized void update(String userId, int id, String content, double importance, String embeddingJson) {
        updateClassified(userId, id, content, importance, embeddingJson, null, null, null);
    }

    public synchronized void updateClassified(String userId, int id, String content, double importance,
                                              String embeddingJson, String category, String tagsJson,
                                              String slotHint) {
        Connection c = pg.connection();
        if (c == null) {
            for (Row row : memoryData.getOrDefault(userId, List.of())) {
                if (row.id != id) continue;
                row.content = content;
                row.importance = importance;
                row.embedding = parseDoubles(embeddingJson);
                row.lastAccessed = new Timestamp(System.currentTimeMillis());
                if (category != null) row.category = category;
                if (tagsJson != null) row.tags = parseStrings(tagsJson);
                if (slotHint != null) row.slotHint = slotHint;
                return;
            }
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE long_term_memory SET content = ?, importance = ?, embedding = ?::jsonb, " +
                        "category = COALESCE(?, category), tags = COALESCE(?::jsonb, tags), " +
                        "slot_hint = COALESCE(?, slot_hint), last_accessed = NOW() WHERE id = ? AND user_id = ?")) {
            ps.setString(1, content); ps.setDouble(2, importance);
            ps.setString(3, embeddingJson); ps.setString(4, category); ps.setString(5, tagsJson);
            ps.setString(6, slotHint); ps.setInt(7, id); ps.setString(8, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("长期记忆更新失败 (id={}): {}", id, e.getMessage());
        }
    }

    public synchronized void deleteAll(String userId, List<Integer> ids) {
        Connection c = pg.connection();
        if (ids == null || ids.isEmpty()) return;
        if (c == null) {
            memoryData.computeIfPresent(userId, (ignored, rows) -> {
                rows.removeIf(row -> ids.contains(row.id));
                return rows;
            });
            return;
        }
        String placeholders = String.join(",", ids.stream().map(i -> "?").toArray(String[]::new));
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM long_term_memory WHERE user_id = ? AND id IN (" + placeholders + ")")) {
            ps.setString(1, userId);
            for (int i = 0; i < ids.size(); i++) ps.setInt(i + 2, ids.get(i));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("长期记忆批量删除失败: {}", e.getMessage());
        }
    }

    public synchronized void recordRecall(String userId, int id, double effectiveImportance, int recallCount,
                                          Timestamp lastRecalledAt) {
        Connection c = pg.connection();
        if (c == null) {
            for (Row row : memoryData.getOrDefault(userId, List.of())) {
                if (row.id != id) continue;
                if (recallCount >= row.recallCount) {
                    row.recallCount = recallCount;
                    row.lastRecalledAt = lastRecalledAt;
                    row.lastAccessed = lastRecalledAt;
                    row.importance = effectiveImportance;
                }
                return;
            }
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE long_term_memory SET recall_count = GREATEST(recall_count, ?), " +
                        "last_recalled_at = CASE WHEN last_recalled_at IS NULL OR last_recalled_at < ? THEN ? ELSE last_recalled_at END, " +
                        "last_accessed = CASE WHEN last_accessed IS NULL OR last_accessed < ? THEN ? ELSE last_accessed END, " +
                        "importance = GREATEST(importance, ?) WHERE id = ? AND user_id = ?")) {
            ps.setInt(1, Math.max(0, recallCount));
            ps.setTimestamp(2, lastRecalledAt); ps.setTimestamp(3, lastRecalledAt);
            ps.setTimestamp(4, lastRecalledAt); ps.setTimestamp(5, lastRecalledAt);
            ps.setDouble(6, effectiveImportance); ps.setInt(7, id); ps.setString(8, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("长期记忆召回统计更新失败 (id={}): {}", id, e.getMessage());
        }
    }

    public synchronized void updateMetrics(String userId, int id, double importance, double baseImportance,
                                           int recallCount, Timestamp lastRecalledAt) {
        Connection c = pg.connection();
        if (c == null) {
            for (Row row : memoryData.getOrDefault(userId, List.of())) {
                if (row.id != id) continue;
                row.baseImportance = baseImportance;
                int normalizedRecallCount = Math.max(0, recallCount);
                if (normalizedRecallCount >= row.recallCount) {
                    row.importance = importance;
                    row.recallCount = normalizedRecallCount;
                    if (lastRecalledAt != null && (row.lastRecalledAt == null
                            || lastRecalledAt.after(row.lastRecalledAt))) {
                        row.lastRecalledAt = lastRecalledAt;
                    }
                }
                return;
            }
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE long_term_memory SET base_importance = ?, " +
                        "importance = CASE WHEN recall_count <= ? THEN ? ELSE importance END, " +
                        "recall_count = GREATEST(recall_count, ?), " +
                        "last_recalled_at = CASE WHEN ? IS NOT NULL " +
                        "AND (last_recalled_at IS NULL OR last_recalled_at < ?) " +
                        "THEN ? ELSE last_recalled_at END WHERE id = ? AND user_id = ?")) {
            int normalizedRecallCount = Math.max(0, recallCount);
            ps.setDouble(1, baseImportance);
            ps.setInt(2, normalizedRecallCount);
            ps.setDouble(3, importance);
            ps.setInt(4, normalizedRecallCount);
            ps.setTimestamp(5, lastRecalledAt);
            ps.setTimestamp(6, lastRecalledAt);
            ps.setTimestamp(7, lastRecalledAt);
            ps.setInt(8, id);
            ps.setString(9, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("长期记忆指标更新失败 (id={}): {}", id, e.getMessage());
        }
    }

    private int saveMemory(String userId, String content, double importance, String embeddingJson,
                           String category, String tagsJson, String slotHint) {
        Row row = new Row();
        row.id = memoryIds.incrementAndGet();
        row.content = content;
        row.importance = importance;
        row.embedding = parseDoubles(embeddingJson);
        row.createdAt = new Timestamp(System.currentTimeMillis());
        row.lastAccessed = row.createdAt;
        row.category = category == null || category.isBlank() ? "general" : category;
        row.tags = parseStrings(tagsJson);
        row.slotHint = slotHint;
        row.baseImportance = importance;
        row.recallCount = 0;
        memoryData.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(row);
        return row.id;
    }

    private static List<Double> parseDoubles(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) return null;
        try {
            return M.readValue(json, M.getTypeFactory().constructCollectionType(List.class, Double.class));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> parseStrings(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) return new ArrayList<>();
        try {
            return M.readValue(json, M.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private static Row copy(Row source) {
        Row row = new Row();
        row.id = source.id;
        row.content = source.content;
        row.importance = source.importance;
        row.embedding = source.embedding == null ? null : new ArrayList<>(source.embedding);
        row.createdAt = source.createdAt == null ? null : new Timestamp(source.createdAt.getTime());
        row.lastAccessed = source.lastAccessed == null ? null : new Timestamp(source.lastAccessed.getTime());
        row.category = source.category;
        row.tags = source.tags == null ? null : new ArrayList<>(source.tags);
        row.slotHint = source.slotHint;
        row.baseImportance = source.baseImportance;
        row.recallCount = source.recallCount;
        row.lastRecalledAt = source.lastRecalledAt == null ? null : new Timestamp(source.lastRecalledAt.getTime());
        return row;
    }
}
