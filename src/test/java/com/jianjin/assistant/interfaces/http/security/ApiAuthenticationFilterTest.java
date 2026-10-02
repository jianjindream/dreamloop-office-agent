package com.jianjin.assistant.interfaces.http.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ApiAuthenticationFilterTest {
    @Test void validatesBearerTokenAndBuildsWorkspaceIdentity() throws Exception {
        AppConfig config = new AppConfig();
        config.getAuth().setEnabled(true);
        config.getAuth().setTokens(Map.of("secret-token", "alice:workspace-1"));
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(config, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<RequestIdentity> captured = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> captured.set(RequestIdentityHolder.current()));

        assertEquals(200, response.getStatus());
        assertEquals(new RequestIdentity("alice", "workspace-1"), captured.get());
        assertNotNull(response.getHeader("X-Request-Id"));
    }

    @Test void rejectsUnknownTokenWithUnifiedError() throws Exception {
        AppConfig config = new AppConfig();
        config.getAuth().setEnabled(true);
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(config, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sessions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> fail("chain must not run"));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTH_UNAUTHORIZED"));
    }
}
