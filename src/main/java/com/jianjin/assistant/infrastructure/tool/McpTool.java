package com.jianjin.assistant.infrastructure.tool;

import com.jianjin.assistant.model.Tool;
import com.jianjin.assistant.model.ToolParam;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public final class McpTool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient();

    private McpTool() {}

    public static Tool create(String name, String description, String endpoint, List<ToolParam> params) {
        Tool tool = new Tool(name, description, params, p -> {
            try {
                String json = mapper.writeValueAsString(p);
                Request request = new Request.Builder()
                        .url(endpoint)
                        .post(RequestBody.create(json, MediaType.parse("application/json")))
                        .build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new RuntimeException("MCP 返回错误状态 " + response.code() + " [" + endpoint + "]");
                    }
                    return response.body() != null ? response.body().string() : "";
                }
            } catch (Exception e) {
                throw new RuntimeException("MCP 请求失败 [" + endpoint + "]: " + e.getMessage());
            }
        });
        tool.setMcp(true);
        tool.setEndpoint(endpoint);
        return tool;
    }
}
