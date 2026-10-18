package com.jianjin.assistant.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Google Programmable Search / Custom Search JSON API adapter. */
public final class GoogleSearchClient {

    private static final String DEFAULT_API_URL = "https://www.googleapis.com/customsearch/v1";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient();

    private GoogleSearchClient() {}

    public static String search(String query, String apiKey, String engineId, String apiUrl) throws Exception {
        if (isBlank(query)) throw new IllegalArgumentException("Google 搜索关键词不能为空");
        if (isBlank(apiKey) || isBlank(engineId)) {
            throw new IllegalArgumentException("Google Custom Search 配置不完整");
        }
        String endpoint = isBlank(apiUrl) ? DEFAULT_API_URL : apiUrl;
        HttpUrl baseUrl = HttpUrl.parse(endpoint);
        if (baseUrl == null) throw new IllegalArgumentException("Google Search API 地址无效");
        HttpUrl url = baseUrl.newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("cx", engineId)
                .addQueryParameter("q", query)
                .addQueryParameter("num", "5")
                .build();

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("Google Custom Search 返回错误状态 " + response.code());
            }
            return parseResponse(responseBody);
        }
    }

    static String parseResponse(String responseBody) throws Exception {
        JsonNode items = mapper.readTree(responseBody).path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new RuntimeException("Google Custom Search 返回空结果");
        }

        StringBuilder result = new StringBuilder();
        int count = Math.min(5, items.size());
        for (int i = 0; i < count; i++) {
            JsonNode item = items.get(i);
            String title = item.path("title").asText("").trim();
            String snippet = item.path("snippet").asText("").trim();
            String link = item.path("link").asText("").trim();
            if (!title.isEmpty()) result.append("**").append(title).append("**\n");
            if (!snippet.isEmpty()) result.append(snippet).append("\n");
            if (!link.isEmpty()) result.append(link).append("\n");
            result.append("\n");
        }
        String formatted = result.toString().trim();
        if (formatted.isEmpty()) throw new RuntimeException("Google Custom Search 返回空结果");
        return formatted;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
