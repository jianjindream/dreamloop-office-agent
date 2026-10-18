package com.jianjin.assistant.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Microsoft Foundry/Azure OpenAI Web Search adapter (grounded by Bing Search). */
public final class BingSearchClient {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient();

    private BingSearchClient() {}

    public static String search(String query, String apiKey, String endpoint, String model) throws Exception {
        if (isBlank(query)) throw new IllegalArgumentException("Bing 搜索关键词不能为空");
        if (isBlank(apiKey) || isBlank(endpoint) || isBlank(model)) {
            throw new IllegalArgumentException("Bing Web Search 配置不完整");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("tools", List.of(Map.of("type", "web_search")));
        body.put("input", "Search the public web for the following request. Return a concise answer backed by source citations: " + query);

        Request request = new Request.Builder()
                .url(responsesUrl(endpoint))
                .header("api-key", apiKey)
                .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.parse("application/json")))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("Bing Web Search 返回错误状态 " + response.code());
            }
            return parseResponse(responseBody);
        }
    }

    static String parseResponse(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode output = root.path("output");
        boolean searched = false;
        List<String> texts = new ArrayList<>();
        Set<SearchSource> sources = new LinkedHashSet<>();

        if (output.isArray()) {
            for (JsonNode item : output) {
                if ("web_search_call".equals(item.path("type").asText())) searched = true;
                if (!"message".equals(item.path("type").asText())) continue;
                for (JsonNode content : item.path("content")) {
                    if (!"output_text".equals(content.path("type").asText())) continue;
                    String text = content.path("text").asText("").trim();
                    if (!text.isEmpty()) texts.add(text);
                    for (JsonNode annotation : content.path("annotations")) {
                        if (!"url_citation".equals(annotation.path("type").asText())) continue;
                        String url = annotation.path("url").asText("").trim();
                        String title = annotation.path("title").asText(url).trim();
                        if (!url.isEmpty()) sources.add(new SearchSource(title.isEmpty() ? url : title, url));
                    }
                }
            }
        }
        if (!searched) throw new RuntimeException("Bing Web Search 未执行网络检索");
        if (texts.isEmpty()) throw new RuntimeException("Bing Web Search 返回空结果");

        StringBuilder result = new StringBuilder(String.join("\n\n", texts));
        if (!sources.isEmpty()) {
            result.append("\n\n**来源：**\n");
            sources.forEach(source -> result.append("- [").append(source.title()).append("](")
                    .append(source.url()).append(")\n"));
        }
        return result.toString().trim();
    }

    static String responsesUrl(String endpoint) {
        String normalized = endpoint == null ? "" : endpoint.trim().replaceAll("/+$", "");
        if (normalized.endsWith("/responses")) return normalized;
        if (normalized.endsWith("/openai/v1")) return normalized + "/responses";
        return normalized + "/openai/v1/responses";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SearchSource(String title, String url) {}
}
