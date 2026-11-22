package com.jianjin.assistant.domain.graph;

/** 节点对其直接依赖的满足条件。 */
public enum DependencyMode {
    ALL,
    ANY,
    QUORUM,
    OPTIONAL;

    public static DependencyMode fromValue(Object value) {
        if (value == null) return ALL;
        try {
            return valueOf(value.toString().trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return ALL;
        }
    }
}
