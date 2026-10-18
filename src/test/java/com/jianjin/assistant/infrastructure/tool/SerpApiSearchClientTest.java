package com.jianjin.assistant.infrastructure.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerpApiSearchClientTest {

    @Test
    void formatsDirectAnswerKnowledgeGraphAndOrganicResults() throws Exception {
        String json = """
                {
                  "answer_box":{"title":"Java","answer":"Java 21 is an LTS release.","link":"https://example.com/answer"},
                  "knowledge_graph":{"title":"Java","description":"A programming language.",
                    "source":{"link":"https://example.com/kg"}},
                  "organic_results":[
                    {"title":"Java releases","snippet":"Release documentation.","link":"https://example.com/releases"}
                  ]
                }
                """;

        String result = SerpApiSearchClient.parseResponse(json);

        assertTrue(result.contains("Java 21 is an LTS release."));
        assertTrue(result.contains("A programming language."));
        assertTrue(result.contains("**Java releases**"));
        assertTrue(result.contains("https://example.com/releases"));
    }

    @Test
    void acceptsOrganicResultsWithoutAnswerBox() throws Exception {
        String json = """
                {"organic_results":[{"title":"Result","snippet":"Useful evidence.","link":"https://example.com"}]}
                """;

        assertTrue(SerpApiSearchClient.parseResponse(json).contains("Useful evidence."));
    }

    @Test
    void surfacesApiErrorsAndRejectsEmptyResponses() {
        assertThrows(RuntimeException.class,
                () -> SerpApiSearchClient.parseResponse("{\"error\":\"Invalid API key\"}"));
        assertThrows(RuntimeException.class,
                () -> SerpApiSearchClient.parseResponse("{\"organic_results\":[]}"));
    }
}
