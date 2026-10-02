package com.jianjin.assistant.interfaces.http.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.config.AppConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Optional bearer-token authentication with a header-based local-development mode. */
@Component
public class ApiAuthenticationFilter extends OncePerRequestFilter {
    private final AppConfig cfg;
    private final ObjectMapper mapper;

    public ApiAuthenticationFilter(AppConfig cfg, ObjectMapper mapper) {
        this.cfg = cfg;
        this.mapper = mapper;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", requestId);
        AppConfig.AuthConfig auth = cfg.getAuth();
        RequestIdentity identity;
        if (auth.isEnabled()) {
            String header = request.getHeader("Authorization");
            String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : "";
            String subject = auth.getTokens().get(token);
            if (subject == null || subject.isBlank()) {
                writeUnauthorized(response, requestId);
                return;
            }
            String[] parts = subject.split(":", 2);
            identity = new RequestIdentity(parts[0], parts.length > 1 ? parts[1] : auth.getDefaultWorkspace());
        } else {
            identity = new RequestIdentity(safe(request.getHeader("X-User-Id"), "default"),
                    safe(request.getHeader("X-Workspace-Id"), auth.getDefaultWorkspace()));
        }
        RequestIdentityHolder.set(identity);
        request.setAttribute("requestId", requestId);
        try { chain.doFilter(request, response); } finally { RequestIdentityHolder.clear(); }
    }

    private void writeUnauthorized(HttpServletResponse response, String requestId) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "AUTH_UNAUTHORIZED");
        body.put("message", "缺少或无效的访问令牌");
        body.put("error", "缺少或无效的访问令牌");
        body.put("requestId", requestId);
        body.put("timestamp", Instant.now().toString());
        mapper.writeValue(response.getOutputStream(), body);
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim();
        return normalized.matches("[A-Za-z0-9._-]{1,80}") ? normalized : fallback;
    }
}
