package com.jianjin.assistant.interfaces.http.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.service.workspace.WorkspaceAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceAuthorizationFilterTest {
    @Test void viewerCanReadButCannotModifyDocuments() throws Exception {
        WorkspaceAccessService access = mock(WorkspaceAccessService.class);
        var viewer = new WorkspaceAccessService.Member("workspace", "bob", "Bob", "", "viewer", "active", "alice", Instant.now(), Instant.now());
        when(access.current()).thenReturn(viewer);
        WorkspaceAuthorizationFilter filter = new WorkspaceAuthorizationFilter(access, new ObjectMapper());

        MockHttpServletRequest write = new MockHttpServletRequest("POST", "/api/documents");
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(write, blocked, new MockFilterChain());
        assertEquals(403, blocked.getStatus());
        assertTrue(blocked.getContentAsString().contains("WORKSPACE_FORBIDDEN"));

        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/api/documents");
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filter.doFilter(read, allowed, new MockFilterChain());
        assertEquals(200, allowed.getStatus());
    }
}
