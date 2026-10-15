package com.jianjin.assistant.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class MemoryItem {
    private int id;
    private String content;
    /** Stable baseline score; effective importance is recalculated from usage. */
    private double baseImportance;
    private double importance;
    private List<Double> embedding;
    private double score;
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessed;
    private int recallCount;
    private LocalDateTime lastRecalledAt;

    /** 主类别（identity / preference / tool_failure / policy / general），默认 general */
    private String category = "general";
    /** 自由标签 */
    private List<String> tags = new ArrayList<>();
    /** SlotKind 提示，可为空 */
    private String slotHint;

    public MemoryItem() {}
    public MemoryItem(int id, String content, double importance, List<Double> embedding) {
        this.id = id; this.content = content; this.baseImportance = importance;
        this.importance = importance; this.embedding = embedding;
        this.createdAt = LocalDateTime.now(); this.lastAccessed = LocalDateTime.now();
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public double getBaseImportance() { return baseImportance; }
    public void setBaseImportance(double baseImportance) { this.baseImportance = baseImportance; }
    public double getImportance() { return importance; }
    public void setImportance(double importance) { this.importance = importance; }
    public List<Double> getEmbedding() { return embedding; }
    public void setEmbedding(List<Double> embedding) { this.embedding = embedding; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastAccessed() { return lastAccessed; }
    public void setLastAccessed(LocalDateTime lastAccessed) { this.lastAccessed = lastAccessed; }
    public int getRecallCount() { return recallCount; }
    public void setRecallCount(int recallCount) { this.recallCount = Math.max(0, recallCount); }
    public LocalDateTime getLastRecalledAt() { return lastRecalledAt; }
    public void setLastRecalledAt(LocalDateTime lastRecalledAt) { this.lastRecalledAt = lastRecalledAt; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category == null || category.isEmpty() ? "general" : category; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : tags; }
    public String getSlotHint() { return slotHint; }
    public void setSlotHint(String slotHint) { this.slotHint = slotHint; }
}
