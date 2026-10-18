package com.jianjin.assistant.service.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnifiedAgentServiceAuditTest {

    @Test
    void redactsCommonSecretsFromAuditedCommand() {
        String command = "tool --token abc API_KEY=xyz --password=secret --secret 'two words' "
                + "https://example.test?a=1&access_token=leak Authorization: Bearer bearer-secret --name visible";

        assertEquals("tool --token *** API_KEY=*** --password=*** --secret *** "
                        + "https://example.test?a=1&access_token=*** Authorization: Bearer *** --name visible",
                UnifiedAgentService.redactCommand(command));
    }
}
