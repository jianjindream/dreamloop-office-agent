package com.jianjin.assistant.application.chat;

import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.model.TaskState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class SnapshotManagerTest {

    @Test
    void snapshotsAreIsolatedByOwner() {
        SnapshotManager manager = new SnapshotManager(mock(InfrastructureService.class));
        TaskState first = task("task-1");
        TaskState second = task("task-2");

        manager.save("workspace:user-a", first);
        manager.save("workspace:user-b", second);
        manager.clear("workspace:user-a");

        assertEquals(0, manager.snapshots("workspace:user-a").size());
        assertEquals(1, manager.snapshots("workspace:user-b").size());
        assertEquals("task-2", manager.snapshots("workspace:user-b").getFirst().getState().getTaskId());
    }

    private static TaskState task(String id) {
        TaskState task = new TaskState();
        task.setTaskId(id);
        task.setQuery("query");
        return task;
    }
}
