package com.jianjin.assistant.domain.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

public class Sandbox implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Sandbox.class);
    private static final int MAX_PENDING_CONFIRMATIONS = 10_000;

    private final Validator validator;
    private final Executor executor;
    private final ConcurrentMap<String, ConfirmationChallenge> confirmations = new ConcurrentHashMap<>();
    private final Duration confirmationTtl;
    private final Semaphore executionPermits;
    private final long acquireTimeoutMs;
    private final ThreadPoolExecutor auditExecutor;
    private volatile Consumer<ExecResult> auditFn;

    public Sandbox(Validator validator, Executor executor) {
        this(validator, executor, Duration.ofMinutes(5), 4, 1000);
    }

    public Sandbox(Validator validator, Executor executor, Duration confirmationTtl) {
        this(validator, executor, confirmationTtl, 4, 1000);
    }

    public Sandbox(Validator validator, Executor executor, Duration confirmationTtl,
                   int maxConcurrentExecutions, long acquireTimeoutMs) {
        this.validator = validator;
        this.executor = executor;
        this.confirmationTtl = confirmationTtl == null || confirmationTtl.isNegative()
                || confirmationTtl.isZero() ? Duration.ofMinutes(5) : confirmationTtl;
        this.executionPermits = new Semaphore(Math.max(1, maxConcurrentExecutions), true);
        this.acquireTimeoutMs = Math.max(0, acquireTimeoutMs);
        this.auditExecutor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1024), runnable -> {
                    Thread thread = new Thread(runnable, "sandbox-audit");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void setAuditFn(Consumer<ExecResult> fn) { this.auditFn = fn; }

    public Validator validator() { return validator; }

    public String backend() { return executor.backend(); }

    /** Exec 主入口：先校验，再执行，最后审计。 */
    public ExecResult exec(ExecRequest req) {
        if (req == null) throw new IllegalArgumentException("执行请求不能为空");
        ValidationResult validation = validator.validate(req.getCommand());

        ExecResult result = new ExecResult();
        result.setCommand(req.getCommand());
        result.setValidation(validation);
        result.setBackend(executor.backend());
        copyContext(req, result);

        // Block：直接拒绝
        if (validation.getLevel() == RiskLevel.BLOCK) {
            result.setExitCode(-1);
            result.setStderr("[拒绝执行] " + validation.getReason());
            audit(result);
            return result;
        }
        if (validation.getLevel() == RiskLevel.WARN) {
            boolean confirmed = req.isConfirm()
                    && isApprovedConfirmation(req.getConfirmationToken(), req);
            if (!confirmed) {
                String token = reusablePendingToken(req.getConfirmationToken(), req);
                if (token == null) token = issueConfirmation(req);
                result.setExitCode(-2);
                result.setConfirmationToken(token);
                result.setStderr("[需要确认] 该命令触发以下规则：" + validation.getViolations()
                        + "；必须先由用户通过确认接口批准，再重新调用并同时设置 confirm=true 与 confirmation_token");
                audit(result);
                return result;
            }
        }

        boolean acquired = false;
        try {
            acquired = executionPermits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) {
            result.setExitCode(-6);
            result.setStderr("[沙箱繁忙] 并发执行已达到上限，请稍后重试");
            audit(result);
            return result;
        }

        if (validation.getLevel() == RiskLevel.WARN
                && !consumeConfirmation(req.getConfirmationToken(), req)) {
            executionPermits.release();
            String token = reusablePendingToken(req.getConfirmationToken(), req);
            if (token == null) token = issueConfirmation(req);
            result.setExitCode(-2);
            result.setConfirmationToken(token);
            result.setStderr("[需要确认] 确认令牌已失效或已被使用，请重新批准");
            audit(result);
            return result;
        }

        ExecResult execResult;
        try {
            execResult = executor.exec(req);
        } finally {
            executionPermits.release();
        }
        execResult.setCommand(req.getCommand());
        execResult.setValidation(validation);
        execResult.setBackend(executor.backend());
        copyContext(req, execResult);

        audit(execResult);
        return execResult;
    }

    private void audit(ExecResult r) {
        Consumer<ExecResult> fn = this.auditFn;
        if (fn != null) {
            auditExecutor.execute(() -> {
                try {
                    fn.accept(r);
                } catch (Exception e) {
                    log.warn("沙箱审计回调失败: {}", e.getMessage());
                }
            });
        }
    }

    private String issueConfirmation(ExecRequest req) {
        Instant now = Instant.now();
        confirmations.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        if (confirmations.size() >= MAX_PENDING_CONFIRMATIONS) {
            confirmations.keySet().stream().findFirst().ifPresent(confirmations::remove);
        }
        String token = UUID.randomUUID().toString();
        confirmations.put(token, new ConfirmationChallenge(req.getCommand(), req.approvalScope(),
                now.plus(confirmationTtl), false));
        return token;
    }

    private boolean consumeConfirmation(String token, ExecRequest req) {
        ConfirmationChallenge challenge = approvedConfirmation(token, req);
        return challenge != null && confirmations.remove(token, challenge);
    }

    private boolean isApprovedConfirmation(String token, ExecRequest req) {
        return approvedConfirmation(token, req) != null;
    }

    private ConfirmationChallenge approvedConfirmation(String token, ExecRequest req) {
        if (token == null || token.isBlank()) return null;
        ConfirmationChallenge challenge = confirmations.get(token);
        if (challenge == null || !challenge.approved() || challenge.expiresAt().isBefore(Instant.now())
                || !challenge.command().equals(req.getCommand())
                || !challenge.approvalScope().equals(req.approvalScope())) return null;
        return challenge;
    }

    private String reusablePendingToken(String token, ExecRequest req) {
        if (token == null || token.isBlank()) return null;
        ConfirmationChallenge challenge = confirmations.get(token);
        return challenge != null && !challenge.expiresAt().isBefore(Instant.now())
                && challenge.command().equals(req.getCommand())
                && challenge.approvalScope().equals(req.approvalScope()) ? token : null;
    }

    /** User-facing approval step. Tool/model calls cannot approve their own challenge. */
    public ApprovalStatus approve(String token, String userId, String workspaceId, String sessionId) {
        if (token == null || token.isBlank()) return ApprovalStatus.NOT_FOUND;
        ConfirmationChallenge current = confirmations.get(token);
        if (current == null) return ApprovalStatus.NOT_FOUND;
        if (current.expiresAt().isBefore(Instant.now())) {
            confirmations.remove(token, current);
            return ApprovalStatus.EXPIRED;
        }
        String scope = safe(workspaceId, "default") + "\n" + safe(userId, "default") + "\n"
                + safe(sessionId, "default");
        if (!current.approvalScope().equals(scope)) return ApprovalStatus.FORBIDDEN;
        confirmations.replace(token, current, current.approve());
        return ApprovalStatus.APPROVED;
    }

    private static void copyContext(ExecRequest req, ExecResult result) {
        result.setUserId(req.getUserId());
        result.setWorkspaceId(req.getWorkspaceId());
        result.setSessionId(req.getSessionId());
        result.setRequestId(req.getRequestId());
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public void close() {
        auditExecutor.shutdown();
        try {
            if (!auditExecutor.awaitTermination(2, TimeUnit.SECONDS)) auditExecutor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            auditExecutor.shutdownNow();
        }
        confirmations.clear();
    }

    public enum ApprovalStatus { APPROVED, NOT_FOUND, EXPIRED, FORBIDDEN }

    private record ConfirmationChallenge(String command, String approvalScope,
                                         Instant expiresAt, boolean approved) {
        ConfirmationChallenge approve() {
            return approved ? this : new ConfirmationChallenge(command, approvalScope, expiresAt, true);
        }
    }
}
