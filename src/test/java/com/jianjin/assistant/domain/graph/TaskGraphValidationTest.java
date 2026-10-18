package com.jianjin.assistant.domain.graph;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskGraphValidationTest {

    @Test
    void rejectsDuplicateNodeIds() {
        Node first = new Node("n1", NodeType.TOOL, "first", "first",
                new HashMap<>(), List.of(), "");
        Node second = new Node("n1", NodeType.TOOL, "second", "second",
                new HashMap<>(), List.of(), "");

        assertThrows(IllegalStateException.class, () -> new TaskGraph(List.of(first, second)));
    }

    @Test
    void rejectsRaceCandidatesWithDifferentDependencies() {
        Node source = new Node("n1", NodeType.TOOL, "source", "source",
                new HashMap<>(), List.of(), "");
        Node left = new Node("n2", NodeType.TOOL, "left", "left",
                new HashMap<>(), List.of("n1"), "search");
        Node right = new Node("n3", NodeType.TOOL, "right", "right",
                new HashMap<>(), List.of(), "search");
        TaskGraph graph = new TaskGraph(List.of(source, left, right));

        assertThrows(IllegalStateException.class, graph::validate);
    }
}
