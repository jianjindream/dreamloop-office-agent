package com.jianjin.assistant.domain.sandbox;

import com.jianjin.assistant.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
            assertEquals(Sandbox.ApprovalStatus.APPROVED,
                    sandbox.approve(challenge.getConfirmationToken(), "default", "default", "default"));

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
    void confirmationIsBoundToUserWorkspaceAndSession() {
        RecordingExecutor executor = new RecordingExecutor();
        try (Sandbox sandbox = sandbox(executor)) {
            ExecRequest request = new ExecRequest("rm temp.txt");
            request.setUserId("alice");
            request.setWorkspaceId("team-a");
            request.setSessionId("s1");
            ExecResult challenge = sandbox.exec(request);

            assertEquals(Sandbox.ApprovalStatus.FORBIDDEN,
                    sandbox.approve(challenge.getConfirmationToken(), "bob", "team-a", "s1"));
            assertEquals(Sandbox.ApprovalStatus.FORBIDDEN,
                    sandbox.approve(challenge.getConfirmationToken(), "alice", "team-a", "s2"));
            assertEquals(Sandbox.ApprovalStatus.APPROVED,
                    sandbox.approve(challenge.getConfirmationToken(), "alice", "team-a", "s1"));

            ExecRequest confirmed = new ExecRequest("rm temp.txt", null, true);
            confirmed.setUserId("alice");
            confirmed.setWorkspaceId("team-a");
            confirmed.setSessionId("s1");
            confirmed.setConfirmationToken(challenge.getConfirmationToken());
            assertEquals(0, sandbox.exec(confirmed).getExitCode());
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

    @Test
    void rejectsExecutionWhenGlobalConcurrencyLimitIsBusy() throws Exception {
        BlockingExecutor executor = new BlockingExecutor();
        try (Sandbox sandbox = new Sandbox(new Validator(new AppConfig.SecurityConfig()), executor,
                Duration.ofMinutes(1), 1, 20)) {
            Thread first = new Thread(() -> sandbox.exec(new ExecRequest("echo first")));
            first.start();
            assertEquals(true, executor.entered.await(1, TimeUnit.SECONDS));

            ExecResult busy = sandbox.exec(new ExecRequest("echo second"));
            assertEquals(-6, busy.getExitCode());

            executor.release.countDown();
            first.join(1000);
        }
    }

    @Test
    void busySandboxDoesNotConsumeApprovedToken() throws Exception {
        BlockingExecutor executor = new BlockingExecutor();
        try (Sandbox sandbox = new Sandbox(new Validator(new AppConfig.SecurityConfig()), executor,
                Duration.ofMinutes(1), 1, 20)) {
            ExecResult challenge = sandbox.exec(new ExecRequest("rm temp.txt"));
            assertEquals(Sandbox.ApprovalStatus.APPROVED,
                    sandbox.approve(challenge.getConfirmationToken(), "default", "default", "default"));
            ExecRequest confirmed = new ExecRequest("rm temp.txt", null, true);
            confirmed.setConfirmationToken(challenge.getConfirmationToken());

            Thread first = new Thread(() -> sandbox.exec(new ExecRequest("echo first")));
            first.start();
            assertEquals(true, executor.entered.await(1, TimeUnit.SECONDS));
            assertEquals(-6, sandbox.exec(confirmed).getExitCode());

            executor.release.countDown();
            first.join(1000);
            assertEquals(0, sandbox.exec(confirmed).getExitCode());
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

    private static final class BlockingExecutor implements Executor {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override public ExecResult exec(ExecRequest req) {
            entered.countDown();
            try { release.await(1, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            ExecResult result = new ExecResult();
            result.setExitCode(0);
            return result;
        }

        @Override public String backend() { return "blocking-test"; }
        @Override public boolean available() { return true; }
    }
}
