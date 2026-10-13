package com.jianjin.assistant.service.tools;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.domain.sandbox.ExecRequest;
import com.jianjin.assistant.domain.sandbox.ExecResult;
import com.jianjin.assistant.domain.sandbox.Executor;
import com.jianjin.assistant.domain.sandbox.Sandbox;
import com.jianjin.assistant.domain.sandbox.Validator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;

class ExecCommandToolTest {

    @Test
    void leavesTimeoutUnsetSoConfiguredSandboxTimeoutIsUsed() {
        RecordingExecutor executor = new RecordingExecutor();
        try (Sandbox sandbox = new Sandbox(new Validator(new AppConfig.SecurityConfig()), executor)) {
            ExecCommandTool.create(sandbox).getExecute().apply(Map.of("command", "echo hello"));

            assertNull(executor.request.getTimeout());
        }
    }

    private static final class RecordingExecutor implements Executor {
        private ExecRequest request;

        @Override
        public ExecResult exec(ExecRequest req) {
            request = req;
            ExecResult result = new ExecResult();
            result.setExitCode(0);
            return result;
        }

        @Override public String backend() { return "test"; }
        @Override public boolean available() { return true; }
    }
}
