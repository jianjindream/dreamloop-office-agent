package com.jianjin.assistant.domain.sandbox;

import com.jianjin.assistant.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SandboxTest {

    @Test
    void blocksDangerousCommandWithoutCallingExecutor() {
        RecordingExecutor executor = new RecordingExecutor();
        try (Sandbox sandbox = sandbox(executor)) {
            ExecResult result = sandbox.exec(new ExecRequest("sudo reboot"));

            assertEquals(-1, result.getExitCode());
            assertEquals(RiskLevel.BLOCK, result.getValidation().getLevel());
            assertEquals(0, executor.calls.get());
        }
    }

    @Test
    void warnCommandRequiresBoundSingleUseToken() {
        RecordingExecutor executor = new RecordingExecutor();
        try (Sandbox sandbox = sandbox(executor)) {
            ExecRequest preconfirmed = new ExecRequest("rm temp.txt", null, true);
            ExecResult challenge = sandbox.exec(preconfirmed);

            assertEquals(-2, challenge.getExitCode());
            assertNotNull(challenge.getConfirmationToken());
            assertEquals(0, executor.calls.get());

            ExecRequest confirmed = new ExecRequest("rm temp.txt", null, true);
            confirmed.setConfirmationToken(challenge.getConfirmationToken());
            assertEquals(0, sandbox.exec(confirmed).getExitCode());
            assertEquals(1, executor.calls.get());

            ExecResult replay = sandbox.exec(confirmed);
            assertEquals(-2, replay.getExitCode());
            assertNotEquals(challenge.getConfirmationToken(), replay.getConfirmationToken());
            assertEquals(1, executor.calls.get());
        }
    }

    @Test
    void tokenCannotAuthorizeDifferentCommand() {
        RecordingExecutor executor = new RecordingExecutor();
        try (Sandbox sandbox = sandbox(executor)) {
            ExecResult challenge = sandbox.exec(new ExecRequest("rm first.txt"));
            ExecRequest changed = new ExecRequest("rm second.txt", null, true);
            changed.setConfirmationToken(challenge.getConfirmationToken());

            ExecResult result = sandbox.exec(changed);

            assertEquals(-2, result.getExitCode());
            assertEquals(0, executor.calls.get());
        }
    }

    @Test
    void safeCommandExecutesImmediately() {
        RecordingExecutor executor = new RecordingExecutor();
        try (Sandbox sandbox = sandbox(executor)) {
            ExecResult result = sandbox.exec(new ExecRequest("echo hello"));

            assertEquals(0, result.getExitCode());
            assertEquals(RiskLevel.SAFE, result.getValidation().getLevel());
            assertEquals(1, executor.calls.get());
            assertFalse(result.isKilled());
        }
    }

    private static Sandbox sandbox(Executor executor) {
        return new Sandbox(new Validator(new AppConfig.SecurityConfig()), executor, Duration.ofMinutes(1));
    }

    private static final class RecordingExecutor implements Executor {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ExecResult exec(ExecRequest req) {
            calls.incrementAndGet();
            ExecResult result = new ExecResult();
            result.setExitCode(0);
            return result;
        }

        @Override public String backend() { return "test"; }
        @Override public boolean available() { return true; }
    }
}
