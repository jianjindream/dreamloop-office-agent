package com.jianjin.assistant.application.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatTaskRegistryTest {
    @Test void tasksAreIndependentAndOwnerScoped() {
        ChatTaskRegistry registry = new ChatTaskRegistry();
        var first = registry.create("workspace-a:user-a");
        var second = registry.create("workspace-a:user-a");

        assertNotEquals(first.taskId(), second.taskId());
        assertFalse(registry.cancel(first.taskId(), "workspace-b:user-a"));
        assertTrue(registry.cancel(first.taskId(), "workspace-a:user-a"));
        assertTrue(first.cancelled().get());
        assertFalse(second.cancelled().get());
        registry.complete(first.taskId());
        assertFalse(registry.cancel(first.taskId(), "workspace-a:user-a"));
    }
}
