package com.jianjin.assistant.domain.promptctx.source;

import com.jianjin.assistant.domain.promptctx.ContextItem;
import com.jianjin.assistant.domain.promptctx.ContextSource;
import com.jianjin.assistant.domain.promptctx.Query;
import com.jianjin.assistant.domain.promptctx.Slot;
import com.jianjin.assistant.domain.promptctx.SlotKind;
import com.jianjin.assistant.model.MemoryItem;
import com.jianjin.assistant.service.memory.LongTermMemory;
import com.jianjin.assistant.service.memory.PreferenceMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ProfileSource implements ContextSource {

    private final PreferenceMemory pref;
    private final LongTermMemory ltm;

    public ProfileSource(PreferenceMemory pref, LongTermMemory ltm) {
        this.pref = pref; this.ltm = ltm;
    }

    @Override
    public String id() { return "profile"; }

    @Override
    public boolean supports(SlotKind kind) { return kind == SlotKind.PROFILE; }

    @Override
    public List<ContextItem> fetch(Slot slot, Query q) {
        List<ContextItem> items = new ArrayList<>();
        if (pref != null) {
            // 取一次性快照，避免遍历期间被并发写入打断
            Map<String, String> data = new TreeMap<>(pref.getData());
            for (Map.Entry<String, String> e : data.entrySet()) {
                items.add(new ContextItem(e.getKey() + ": " + e.getValue(), 1.0, id()));
            }
        }
        if (ltm != null && !slot.getFilter().getCategories().isEmpty()) {
            int limit = slot.getFilter().getTopK();
            if (limit <= 0) limit = 10;
            int count = 0;
            for (MemoryItem item : ltm.getItems()) {
                if (count >= limit) break;
                String category = item.getCategory() == null ? "general" : item.getCategory();
                if (!slot.getFilter().getCategories().contains(category)) continue;
                items.add(new ContextItem(item.getContent(), item.getImportance(), id()));
                count++;
            }
        }
        return items;
    }
}
