package com.jianjin.assistant.infrastructure.sandbox;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.domain.sandbox.Executor;
import com.jianjin.assistant.domain.sandbox.Sandbox;
import com.jianjin.assistant.domain.sandbox.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public final class SandboxFactory {

    private static final Logger log = LoggerFactory.getLogger(SandboxFactory.class);

    private SandboxFactory() {}

    public static Sandbox build(String backend,
                                AppConfig.SandboxConfig sandboxCfg,
                                AppConfig.SecurityConfig secCfg) {
        validateConfiguration(backend, sandboxCfg, secCfg);
        Validator validator = new Validator(secCfg);
        Executor exec;
        switch (backend == null ? "" : backend) {
            case "docker" -> {
                DockerSandbox ds = new DockerSandbox(sandboxCfg);
                if (!ds.available()) log.error("Docker 不可用；沙箱将失败关闭，不会模拟执行命令");
                exec = ds;
            }
            case "local" -> exec = new LocalSandbox(sandboxCfg, secCfg);
            case "mock" -> exec = new MockSandbox();
            default -> throw new IllegalArgumentException("未知沙箱后端: " + backend);
        }
        return new Sandbox(validator, exec,
                Duration.ofSeconds(secCfg.getConfirmationTtlSeconds()),
                sandboxCfg.getMaxConcurrentExecutions(), sandboxCfg.getAcquireTimeoutMs());
    }

    private static void validateConfiguration(String backend, AppConfig.SandboxConfig cfg,
                                              AppConfig.SecurityConfig security) {
        if (cfg == null || security == null) throw new IllegalArgumentException("沙箱配置不能为空");
        if (cfg.getTimeoutMs() < 100 || cfg.getTimeoutMs() > 300_000)
            throw new IllegalArgumentException("sandbox.timeout-ms 必须在 100..300000 之间");
        if (cfg.getMaxOutputBytes() < 1024 || cfg.getMaxOutputBytes() > 10 * 1024 * 1024)
            throw new IllegalArgumentException("sandbox.max-output-bytes 必须在 1024..10485760 之间");
        if (cfg.getMemoryLimitMb() < 16 || cfg.getMemoryLimitMb() > 4096)
            throw new IllegalArgumentException("sandbox.memory-limit-mb 必须在 16..4096 之间");
        if (cfg.getCpuPercent() < 1 || cfg.getCpuPercent() > 100)
            throw new IllegalArgumentException("sandbox.cpu-percent 必须在 1..100 之间");
        if (cfg.getMaxPids() < 4 || cfg.getMaxPids() > 4096)
            throw new IllegalArgumentException("sandbox.max-pids 必须在 4..4096 之间");
        if (cfg.getMaxConcurrentExecutions() < 1 || cfg.getMaxConcurrentExecutions() > 64)
            throw new IllegalArgumentException("sandbox.max-concurrent-executions 必须在 1..64 之间");
        if (cfg.getAcquireTimeoutMs() < 0 || cfg.getAcquireTimeoutMs() > 60_000)
            throw new IllegalArgumentException("sandbox.acquire-timeout-ms 必须在 0..60000 之间");
        if (security.getConfirmationTtlSeconds() < 1 || security.getConfirmationTtlSeconds() > 3600)
            throw new IllegalArgumentException("security.confirmation-ttl-seconds 必须在 1..3600 之间");
        if ("docker".equals(backend) && (!cfg.isNetworkDisabled() || !cfg.isReadonlyRootfs()))
            throw new IllegalArgumentException("Docker 安全沙箱必须启用 network-disabled 和 readonly-rootfs");
        if ("local".equals(backend)) {
            if (!cfg.isAllowUnsafeLocalBackend())
                throw new IllegalArgumentException("Local 后端必须显式设置 allow-unsafe-local-backend=true");
            if (!security.isAllowlistMode() || security.getAllowlist() == null || security.getAllowlist().isEmpty())
                throw new IllegalArgumentException("Local 后端必须启用非空命令白名单");
        }
    }
}
