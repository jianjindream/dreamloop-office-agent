package com.jianjin.assistant.infrastructure.sandbox;

import com.jianjin.assistant.config.AppConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxFactoryTest {

    @Test
    void rejectsUnknownBackendInsteadOfSilentlyUsingMock() {
        assertThrows(IllegalArgumentException.class, () -> SandboxFactory.build(
                "typo", new AppConfig.SandboxConfig(), new AppConfig.SecurityConfig()));
    }

    @Test
    void localBackendRequiresExplicitOptInAndAllowlist() {
        AppConfig.SandboxConfig sandbox = new AppConfig.SandboxConfig();
        AppConfig.SecurityConfig security = new AppConfig.SecurityConfig();

        assertThrows(IllegalArgumentException.class,
                () -> SandboxFactory.build("local", sandbox, security));

        sandbox.setAllowUnsafeLocalBackend(true);
        assertThrows(IllegalArgumentException.class,
                () -> SandboxFactory.build("local", sandbox, security));
    }
}
