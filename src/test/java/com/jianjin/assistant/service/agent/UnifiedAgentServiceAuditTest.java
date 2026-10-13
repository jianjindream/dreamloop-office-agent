package com.jianjin.assistant.service.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnifiedAgentServiceAuditTest {

    @Test
    void redactsCommonSecretsFromAuditedCommand() {
        String command = "tool --token abc API_KEY=xyz --password=secret --secret 'two words' --name visible";

        assertEquals("tool --token *** API_KEY=*** --password=*** --secret *** --name visible",
                UnifiedAgentService.redactCommand(command));
    }
}
