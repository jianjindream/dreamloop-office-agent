package com.jianjin.assistant.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** SerpAPI Google Search adapter. */
public final class SerpApiSearchClient {

    private static final String DEFAULT_API_URL = "https://serpapi.com/search.json";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient();

    private SerpApiSearchClient() {}

    public static String search(String query, String apiKey, String apiUrl) throws Exception {
        if (isBlank(query)) throw new IllegalArgumentException("SerpAPI 搜索关键词不能为空");
        if (isBlank(apiKey)) throw new IllegalArgumentException("SerpAPI Key 未配置");

        String endpoint = isBlank(apiUrl) ? DEFAULT_API_URL : apiUrl;
        HttpUrl baseUrl = HttpUrl.parse(endpoint);
        if (baseUrl == null) throw new IllegalArgumentException("SerpAPI 地址无效");
        HttpUrl url = baseUrl.newBuilder()
                .addQueryParameter("engine", "google")
                .addQueryParameter("q", query)
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("num", "5")
                .addQueryParameter("output", "json")
                .build();

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("SerpAPI 返回错误状态 " + response.code());
            }
            return parseResponse(responseBody);
        }
    }

    static String parseResponse(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        String error = root.path("error").asText("").trim();
        if (!error.isEmpty()) throw new RuntimeException("SerpAPI 返回错误: " + error);

        StringBuilder result = new StringBuilder();
        appendDirectAnswer(result, root.path("answer_box"));
        appendKnowledgeGraph(result, root.path("knowledge_graph"));

        JsonNode organicResults = root.path("organic_results");
        if (organicResults.isArray()) {
            int count = Math.min(5, organicResults.size());
            for (int i = 0; i < count; i++) {
                appendSearchResult(result, organicResults.get(i));
            }
        }

        String formatted = result.toString().trim();
        if (formatted.isEmpty()) throw new RuntimeException("SerpAPI 返回空结果");
        return formatted;
    }

    private static void appendDirectAnswer(StringBuilder result, JsonNode answerBox) {
        if (!answerBox.isObject()) return;
        String title = answerBox.path("title").asText("").trim();
        String answer = firstText(answerBox, "answer", "snippet", "result");
        String link = answerBox.path("link").asText("").trim();
        appendBlock(result, title.isEmpty() ? "直接答案" : title, answer, link);
    }

    private static void appendKnowledgeGraph(StringBuilder result, JsonNode knowledgeGraph) {
        if (!knowledgeGraph.isObject()) return;
        appendBlock(result,
                knowledgeGraph.path("title").asText("知识图谱").trim(),
                knowledgeGraph.path("description").asText("").trim(),
                knowledgeGraph.path("source").path("link").asText("").trim());
    }

    private static void appendSearchResult(StringBuilder result, JsonNode item) {
        appendBlock(result,
                item.path("title").asText("").trim(),
                item.path("snippet").asText("").trim(),
                item.path("link").asText("").trim());
    }

    private static void appendBlock(StringBuilder result, String title, String text, String link) {
        if (title.isEmpty() && text.isEmpty() && link.isEmpty()) return;
        if (!title.isEmpty()) result.append("**").append(title).append("**\n");
        if (!text.isEmpty()) result.append(text).append("\n");
        if (!link.isEmpty()) result.append(link).append("\n");
        result.append("\n");
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
