package com.jianjin.assistant.interfaces.http.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.interfaces.http.error.ApiException;
import com.jianjin.assistant.service.workspace.WorkspaceAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class WorkspaceAuthorizationFilter extends OncePerRequestFilter {
    private final WorkspaceAccessService access;
    private final ObjectMapper mapper;

    public WorkspaceAuthorizationFilter(WorkspaceAccessService access, ObjectMapper mapper) { this.access = access; this.mapper = mapper; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) || !request.getRequestURI().startsWith("/api/")
                || "/api/status".equals(request.getRequestURI());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            WorkspaceAccessService.Member member = access.current();
            String method = request.getMethod();
            String path = request.getRequestURI();
            boolean mutation = !ListMethods.READ.contains(method);
            if (mutation && (path.startsWith("/api/team") || path.startsWith("/api/connections") || path.startsWith("/api/tools/mcp"))) {
                if (!member.administrator()) throw forbidden("该操作需要工作区管理员权限");
            } else if (mutation && contentMutation(path)) {
                if (!member.editor()) throw forbidden("当前角色为只读成员，不能修改工作区内容");
            }
            chain.doFilter(request, response);
        } catch (ApiException error) {
            response.setStatus(error.getStatus().value()); response.setContentType("application/json;charset=UTF-8");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", error.getCode()); body.put("message", error.getMessage()); body.put("error", error.getMessage());
            body.put("requestId", RequestIdentityHolder.current().requestId()); body.put("timestamp", Instant.now().toString());
            mapper.writeValue(response.getOutputStream(), body);
        }
    }

    private static boolean contentMutation(String path) {
        return path.startsWith("/api/documents") || path.startsWith("/api/upload") || path.startsWith("/api/docs")
                || path.startsWith("/api/weekly-report") || path.startsWith("/api/tasks")
                || path.startsWith("/api/sandbox/confirm");
    }

    private static ApiException forbidden(String message) { return new ApiException("WORKSPACE_FORBIDDEN", message, HttpStatus.FORBIDDEN); }
    private static final class ListMethods { private static final java.util.Set<String> READ = java.util.Set.of("GET", "HEAD"); }
}
