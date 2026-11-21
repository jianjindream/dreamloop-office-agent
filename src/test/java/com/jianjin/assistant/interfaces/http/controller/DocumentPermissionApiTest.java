package com.jianjin.assistant.interfaces.http.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.application.chat.ChatApplicationService;
import com.jianjin.assistant.application.chat.ChatTaskRegistry;
import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.config.WorkspaceStorageConfig;
import com.jianjin.assistant.dto.ChatRequest;
import com.jianjin.assistant.dto.ChatResponse;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.infrastructure.persistence.JdbcLibraryRepo;
import com.jianjin.assistant.infrastructure.persistence.WorkspaceDatabase;
import com.jianjin.assistant.interfaces.http.error.GlobalApiExceptionHandler;
import com.jianjin.assistant.interfaces.http.security.ApiAuthenticationFilter;
import com.jianjin.assistant.interfaces.http.security.RequestIdentity;
import com.jianjin.assistant.interfaces.http.security.RequestIdentityHolder;
import com.jianjin.assistant.interfaces.http.security.WorkspaceAuthorizationFilter;
import com.jianjin.assistant.service.agent.UnifiedAgentService;
import com.jianjin.assistant.service.document.AttachmentStore;
import com.jianjin.assistant.service.document.DocumentAccessService;
import com.jianjin.assistant.service.document.DocumentLibraryService;
import com.jianjin.assistant.service.document.DocumentParser;
import com.jianjin.assistant.service.document.UploadProgressService;
import com.jianjin.assistant.service.rag.RagService;
import com.jianjin.assistant.service.tools.ToolService;
import com.jianjin.assistant.service.workspace.WorkspaceAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentPermissionApiTest {
    @TempDir Path directory;
    WorkspaceDatabase database;
    MockMvc mvc;
    ChatApplicationService chat;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void setup() {
        WorkspaceStorageConfig storage = new WorkspaceStorageConfig(); storage.setDirectory(directory.toString());
        AppConfig app = new AppConfig();
        database = new WorkspaceDatabase(storage, app);
        JdbcLibraryRepo repo = new JdbcLibraryRepo(database);
        DocumentAccessService documentAccess = new DocumentAccessService(database, repo);
        WorkspaceAccessService workspaceAccess = new WorkspaceAccessService(database);
        RequestIdentity alice = new RequestIdentity("alice", "team-a");
        try (var ignored = RequestIdentityHolder.bind(alice)) {
            workspaceAccess.current();
            workspaceAccess.invite("bob", "Bob", "bob@example.com", "viewer");
        }
        try (var ignored = RequestIdentityHolder.bind(new RequestIdentity("bob", "team-a"))) { workspaceAccess.current(); }

        RagService rag = mock(RagService.class);
        doReturn(Map.entry(2, "shared-hash")).when(rag).ingest(anyString(), anyString(), anyString());
        DocumentLibraryService library = new DocumentLibraryService(repo, rag);
        library.setDocumentAccess(documentAccess);
        chat = mock(ChatApplicationService.class);
        when(chat.process(any(ChatRequest.class), any(AtomicBoolean.class))).thenReturn(new ChatResponse());
        UnifiedAgentService agent = mock(UnifiedAgentService.class);
        when(agent.getTools()).thenReturn(Map.of()); when(agent.getRagService()).thenReturn(rag);
        AgentController controller = new AgentController(chat, agent, mock(InfrastructureService.class), app,
                mock(ToolService.class), new DocumentParser(), library, new ChatTaskRegistry(),
                new UploadProgressService(), new AttachmentStore(storage), documentAccess);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .addFilters(new ApiAuthenticationFilter(app, mapper), new WorkspaceAuthorizationFilter(workspaceAccess, mapper))
                .build();
    }

    @AfterEach void close() { database.close(); }

    @Test void shareReadSelectedRagAndRevokeAreEnforcedServerSide() throws Exception {
        String created = mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "alice").header("X-Workspace-Id", "team-a")
                        .content("{\"title\":\"预算\",\"content_md\":\"预算 100 万\",\"ingest_to_rag\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).path("document").path("id").asText();

        mvc.perform(put("/api/documents/{id}/permissions", id).contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "alice").header("X-Workspace-Id", "team-a")
                        .content("{\"visibility\":\"restricted\",\"entries\":[{\"principal_type\":\"user\",\"principal_id\":\"bob\",\"permission\":\"read\",\"effect\":\"allow\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("restricted"));

        mvc.perform(get("/api/documents/{id}", id).header("X-User-Id", "bob").header("X-Workspace-Id", "team-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.document.accessLevel").value("read"));
        mvc.perform(put("/api/documents/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "bob").header("X-Workspace-Id", "team-a")
                        .content("{\"content_md\":\"tampered\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "bob").header("X-Workspace-Id", "team-a")
                        .content("{\"message\":\"总结预算\",\"session_id\":\"s1\",\"document_ids\":[\"" + id + "\"]}"))
                .andExpect(status().isOk());
        var captor = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat).process(captor.capture(), any(AtomicBoolean.class));
        assertEquals(java.util.Set.of("shared-hash"), captor.getValue().getAllowedDocHashes());

        mvc.perform(put("/api/documents/{id}/permissions", id).contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "alice").header("X-Workspace-Id", "team-a")
                        .content("{\"visibility\":\"private\",\"entries\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/documents/{id}", id).header("X-User-Id", "bob").header("X-Workspace-Id", "team-a"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "bob").header("X-Workspace-Id", "team-a")
                        .content("{\"message\":\"再次读取\",\"session_id\":\"s2\",\"document_ids\":[\"" + id + "\"]}"))
                .andExpect(status().isNotFound());
    }
}
