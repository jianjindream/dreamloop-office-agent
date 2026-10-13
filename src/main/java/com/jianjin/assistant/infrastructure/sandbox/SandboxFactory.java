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
                Duration.ofSeconds(Math.max(1, secCfg.getConfirmationTtlSeconds())));
    }
}
