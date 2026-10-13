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
import java.util.function.Consumer;

public class Sandbox implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Sandbox.class);
    private static final int MAX_PENDING_CONFIRMATIONS = 10_000;

    private final Validator validator;
    private final Executor executor;
    private final ConcurrentMap<String, ConfirmationChallenge> confirmations = new ConcurrentHashMap<>();
    private final Duration confirmationTtl;
    private final ThreadPoolExecutor auditExecutor;
    private volatile Consumer<ExecResult> auditFn;

    public Sandbox(Validator validator, Executor executor) {
        this(validator, executor, Duration.ofMinutes(5));
    }

    public Sandbox(Validator validator, Executor executor, Duration confirmationTtl) {
        this.validator = validator;
        this.executor = executor;
        this.confirmationTtl = confirmationTtl == null || confirmationTtl.isNegative()
                || confirmationTtl.isZero() ? Duration.ofMinutes(5) : confirmationTtl;
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
        ValidationResult validation = validator.validate(req.getCommand());

        ExecResult result = new ExecResult();
        result.setCommand(req.getCommand());
        result.setValidation(validation);
        result.setBackend(executor.backend());

        // Block：直接拒绝
        if (validation.getLevel() == RiskLevel.BLOCK) {
            result.setExitCode(-1);
            result.setStderr("[拒绝执行] " + validation.getReason());
            audit(result);
            return result;
        }
        if (validation.getLevel() == RiskLevel.WARN) {
            boolean confirmed = req.isConfirm()
                    && consumeConfirmation(req.getConfirmationToken(), req.getCommand());
            if (!confirmed) {
                String token = issueConfirmation(req.getCommand());
                result.setExitCode(-2);
                result.setConfirmationToken(token);
                result.setStderr("[需要确认] 该命令触发以下规则：" + validation.getViolations()
                        + "；用户确认后，请重新调用并同时设置 confirm=true 与 confirmation_token");
                audit(result);
                return result;
            }
        }

        ExecResult execResult = executor.exec(req);
        execResult.setCommand(req.getCommand());
        execResult.setValidation(validation);
        execResult.setBackend(executor.backend());

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

    private String issueConfirmation(String command) {
        Instant now = Instant.now();
        confirmations.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        if (confirmations.size() >= MAX_PENDING_CONFIRMATIONS) {
            confirmations.keySet().stream().findFirst().ifPresent(confirmations::remove);
        }
        String token = UUID.randomUUID().toString();
        confirmations.put(token, new ConfirmationChallenge(command, now.plus(confirmationTtl)));
        return token;
    }

    private boolean consumeConfirmation(String token, String command) {
        if (token == null || token.isBlank()) return false;
        ConfirmationChallenge challenge = confirmations.remove(token);
        return challenge != null
                && !challenge.expiresAt().isBefore(Instant.now())
                && challenge.command().equals(command);
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

    private record ConfirmationChallenge(String command, Instant expiresAt) {}
}
