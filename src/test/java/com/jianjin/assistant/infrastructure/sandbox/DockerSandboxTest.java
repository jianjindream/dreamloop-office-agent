package com.jianjin.assistant.infrastructure.sandbox;

import com.jianjin.assistant.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerSandboxTest {

    @Test
    void buildsHardenedResourceLimitedContainerCommand() {
        AppConfig.SandboxConfig config = new AppConfig.SandboxConfig();
        DockerSandbox sandbox = new DockerSandbox(config, true);

        List<String> args = sandbox.buildDockerArgs("echo hello", "sandbox-test");

        assertEquals(List.of("docker", "run", "--rm", "--name", "sandbox-test"), args.subList(0, 5));
        assertTrue(args.containsAll(List.of("no-new-privileges", "ALL", "none", "65534:65534")));
        assertTrue(args.containsAll(List.of("--memory", "256m", "--memory-swap", "--cpus", "0.50")));
        assertTrue(args.containsAll(List.of("--pids-limit", "64", "--read-only", "--network")));
        assertFalse(args.contains("-i"));
        assertEquals(List.of("alpine:3.19", "sh", "-c", "echo hello"),
                args.subList(args.size() - 4, args.size()));
    }
}
