package com.jianjin.assistant.domain.graph;

/** 节点最终失败后的整图处理策略。 */
public enum FailurePolicy {
    /** 阻断当前分支，其他独立分支继续。 */
    BLOCK_BRANCH,
    /** 允许使用其余结果降级完成。 */
    CONTINUE_DEGRADED,
    /** 该节点不可替代，失败后立即结束整图。 */
    FAIL_FAST;

    public static FailurePolicy fromValue(Object value) {
        if (value == null) return BLOCK_BRANCH;
        try {
            return valueOf(value.toString().trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return BLOCK_BRANCH;
        }
    }
}
