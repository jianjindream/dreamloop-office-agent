package com.jianjin.assistant.domain.promptctx;

import java.util.LinkedHashMap;
import java.util.Map;

public class ContextItem {
    private String text;
    private double score;
    /** 调试用：标记来自哪个 ContextSource */
    private String source;
    /** 调试用元数据 */
    private Map<String, String> meta = new LinkedHashMap<>();
    /** Invoked only after this item survives slot and global context budgets. */
    private Runnable includedListener;

    public ContextItem() {}

    public ContextItem(String text) { this.text = text; }

    public ContextItem(String text, double score, String source) {
        this.text = text; this.score = score; this.source = source;
    }

    public ContextItem(String text, double score, String source, Map<String, String> meta) {
        this(text, score, source);
        this.meta = meta != null ? meta : new LinkedHashMap<>();
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Map<String, String> getMeta() { return meta; }
    public void setMeta(Map<String, String> meta) { this.meta = meta; }
    public void setIncludedListener(Runnable includedListener) { this.includedListener = includedListener; }
    public void markIncluded() {
        if (includedListener != null) includedListener.run();
    }
}
