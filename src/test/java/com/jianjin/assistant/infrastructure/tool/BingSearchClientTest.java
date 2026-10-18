package com.jianjin.assistant.infrastructure.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BingSearchClientTest {

    @Test
    void parsesGroundedAnswerAndDeduplicatesCitations() throws Exception {
        String json = """
                {"output":[
                  {"type":"web_search_call","status":"completed"},
                  {"type":"message","content":[{"type":"output_text","text":"Java 21 is an LTS release.",
                    "annotations":[
                      {"type":"url_citation","title":"Oracle Java","url":"https://example.com/java"},
                      {"type":"url_citation","title":"Oracle Java","url":"https://example.com/java"}
                    ]}]}
                ]}
                """;

        String result = BingSearchClient.parseResponse(json);

        assertTrue(result.contains("Java 21 is an LTS release."));
        assertTrue(result.contains("[Oracle Java](https://example.com/java)"));
        assertEquals(result.indexOf("https://example.com/java"), result.lastIndexOf("https://example.com/java"));
    }

    @Test
    void rejectsUngroundedModelOnlyResponse() {
        String json = """
                {"output":[{"type":"message","content":[{"type":"output_text","text":"model answer"}]}]}
                """;

        assertThrows(RuntimeException.class, () -> BingSearchClient.parseResponse(json));
    }

    @Test
    void normalizesAzureResponsesEndpoint() {
        assertEquals("https://demo.openai.azure.com/openai/v1/responses",
                BingSearchClient.responsesUrl("https://demo.openai.azure.com/"));
        assertEquals("https://demo.openai.azure.com/openai/v1/responses",
                BingSearchClient.responsesUrl("https://demo.openai.azure.com/openai/v1"));
    }
}
