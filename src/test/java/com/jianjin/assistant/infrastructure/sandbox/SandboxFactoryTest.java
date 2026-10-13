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
}
