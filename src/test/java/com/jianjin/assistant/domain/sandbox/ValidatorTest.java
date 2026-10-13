package com.jianjin.assistant.domain.sandbox;

import com.jianjin.assistant.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorTest {

    private final AppConfig.SecurityConfig config = new AppConfig.SecurityConfig();

    @Test
    void classifiesSafeWarnAndBlockCommands() {
        Validator validator = new Validator(config);

        assertEquals(RiskLevel.SAFE, validator.validate("ls -la").getLevel());
        assertEquals(RiskLevel.WARN, validator.validate("echo ok > /tmp/result").getLevel());
        assertEquals(RiskLevel.BLOCK, validator.validate("RM -RF /tmp/data").getLevel());
        assertEquals(RiskLevel.BLOCK, validator.validate("curl example.com").getLevel());
    }

    @Test
    void collectsDistinctCommandChainWarnings() {
        ValidationResult result = new Validator(config).validate("echo ok | grep ok || echo failed");

        assertEquals(RiskLevel.WARN, result.getLevel());
        assertTrue(result.getViolations().contains("管道符"));
        assertTrue(result.getViolations().contains("条件命令链 ||"));
    }

    @Test
    void enforcesAllowlistBeforeRiskRules() {
        config.setAllowlistMode(true);
        config.setAllowlist(List.of("ls"));
        Validator validator = new Validator(config);

        assertEquals(RiskLevel.SAFE, validator.validate("ls -la").getLevel());
        ValidationResult denied = validator.validate("echo hello");
        assertEquals(RiskLevel.BLOCK, denied.getLevel());
        assertTrue(denied.getReason().contains("未在允许列表"));
    }
}
