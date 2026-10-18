package com.jianjin.assistant.infrastructure.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleSearchClientTest {

    @Test
    void formatsGoogleItemsAsSearchEvidence() throws Exception {
        String json = """
                {"items":[
                  {"title":"Java 21","snippet":"Java 21 release notes.","link":"https://example.com/java21"},
                  {"title":"Java 22","snippet":"Java 22 release notes.","link":"https://example.com/java22"}
                ]}
                """;

        String result = GoogleSearchClient.parseResponse(json);

        assertTrue(result.contains("**Java 21**"));
        assertTrue(result.contains("Java 21 release notes."));
        assertTrue(result.contains("https://example.com/java22"));
    }

    @Test
    void rejectsEmptyItems() {
        assertThrows(RuntimeException.class, () -> GoogleSearchClient.parseResponse("{\"items\":[]}"));
    }
}
