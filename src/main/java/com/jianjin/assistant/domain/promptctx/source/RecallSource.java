package com.jianjin.assistant.domain.promptctx.source;

import com.jianjin.assistant.domain.promptctx.ContextItem;
import com.jianjin.assistant.domain.promptctx.ContextSource;
import com.jianjin.assistant.domain.promptctx.Query;
import com.jianjin.assistant.domain.promptctx.Slot;
import com.jianjin.assistant.domain.promptctx.SlotKind;
import com.jianjin.assistant.model.MemoryItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class RecallSource implements ContextSource {

    /** 抽象 LongTerm / GraphMemory 共有的过滤召回能力 */
    public interface Recaller {
        List<MemoryItem> recall(String query, int topK, List<Double> queryEmbedding);
    }

    private final Recaller recaller;
    private final int configuredTopK;

    public RecallSource(Recaller recaller) { this(recaller, 0); }

    public RecallSource(Recaller recaller, int configuredTopK) {
        this.recaller = recaller;
        this.configuredTopK = configuredTopK;
    }

    @Override
    public String id() { return "recall"; }

    @Override
    public boolean supports(SlotKind kind) { return kind == SlotKind.RECALL; }

    @Override
    public List<ContextItem> fetch(Slot slot, Query q) {
        if (recaller == null) return List.of();
        int topK = configuredTopK > 0 ? configuredTopK : slot.getFilter().getTopK();
        if (topK <= 0) topK = 3;
        int fetchK = Math.max(topK, topK * 4);
        List<MemoryItem> hits = recaller.recall(q.getText(), fetchK, q.getEmbedding());
        if (hits == null || hits.isEmpty()) return List.of();

        List<ContextItem> items = new ArrayList<>();
        for (MemoryItem h : hits) {
            if (!slot.getFilter().getCategories().isEmpty()) {
                String category = h.getCategory() == null ? "general" : h.getCategory();
                if (!slot.getFilter().getCategories().contains(category)) continue;
            }
            if (!slot.getFilter().getRequireTags().isEmpty()) {
                List<String> tags = h.getTags() == null ? List.of() : h.getTags();
                if (!tags.containsAll(slot.getFilter().getRequireTags())) continue;
            }
            if (slot.getFilter().getMaxAgeHours() > 0 && h.getCreatedAt() != null
                    && ChronoUnit.HOURS.between(h.getCreatedAt(), LocalDateTime.now())
                    > slot.getFilter().getMaxAgeHours()) continue;
            if (slot.getFilter().getMinScore() > 0 && h.getScore() < slot.getFilter().getMinScore()) {
                continue;
            }
            Map<String, String> meta = new LinkedHashMap<>();
            String text = String.format("%s（重要性=%.2f, 综合分=%.2f）",
                    h.getContent(), h.getImportance(), h.getScore());
            items.add(new ContextItem(text, h.getScore(), id(), meta));
            if (items.size() >= topK) break;
        }
        return items;
    }
}
