package com.jianjin.assistant.interfaces.http.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.application.chat.*;
import com.jianjin.assistant.config.*;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.infrastructure.persistence.*;
import com.jianjin.assistant.dto.ChatRequest;
import com.jianjin.assistant.dto.ChatResponse;
import com.jianjin.assistant.interfaces.http.error.GlobalApiExceptionHandler;
import com.jianjin.assistant.interfaces.http.security.ApiAuthenticationFilter;
import com.jianjin.assistant.service.agent.UnifiedAgentService;
import com.jianjin.assistant.service.document.*;
import com.jianjin.assistant.service.rag.RagService;
import com.jianjin.assistant.service.tools.ToolService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentPersistenceApiTest {
    @TempDir Path directory;
    WorkspaceDatabase database;
    MockMvc mvc;
    RagService rag;
    ChatApplicationService chatApplication;
    UnifiedAgentService agent;
    ObjectMapper mapper=new ObjectMapper();

    @BeforeEach void setup() {
        WorkspaceStorageConfig storage=new WorkspaceStorageConfig(); storage.setDirectory(directory.toString());
        AppConfig app=new AppConfig(); database=new WorkspaceDatabase(storage,app); rag=mock(RagService.class);
        chatApplication=mock(ChatApplicationService.class); agent=mock(UnifiedAgentService.class);
        when(agent.getTools()).thenReturn(Map.of());
        when(chatApplication.process(any(ChatRequest.class),any(AtomicBoolean.class))).thenReturn(new ChatResponse());
        var controller=new AgentController(chatApplication,agent,mock(InfrastructureService.class),app,
                mock(ToolService.class),new DocumentParser(),new DocumentLibraryService(new JdbcLibraryRepo(database),rag),
                new ChatTaskRegistry(),new UploadProgressService(),new AttachmentStore(storage));
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalApiExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(),
                        new org.springframework.http.converter.ResourceHttpMessageConverter())
                .addFilters(new ApiAuthenticationFilter(app,mapper)).build();
    }
    @AfterEach void close() { database.close(); }

    @Test void originalsAreProtectedAndRecoverableEvenWhenIndexingFails() throws Exception {
        when(rag.ingest(anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("offline"));
        byte[] original="# 原始周报\r\n\r\n本周完成接口设计。".getBytes(StandardCharsets.UTF_8);
        String response=mvc.perform(multipart("/api/upload/file").file(new MockMultipartFile("file","weekly.md","text/markdown",original))
                .header("X-User-Id","alice").header("X-Workspace-Id","team-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.document_saved").value(true))
                .andExpect(jsonPath("$.index_status").value("failed")).andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(response).path("document_id").asText();
        byte[] downloaded=mvc.perform(get("/api/documents/{id}/original",id).header("X-User-Id","alice").header("X-Workspace-Id","team-a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(original,downloaded);
        mvc.perform(get("/api/documents/{id}/original",id).header("X-User-Id","alice").header("X-Workspace-Id","team-b")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/documents/{id}",id).header("X-User-Id","alice").header("X-Workspace-Id","team-a")).andExpect(status().isOk());
        mvc.perform(get("/api/documents/{id}",id).header("X-User-Id","alice").header("X-Workspace-Id","team-a")).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents?status=deleted").header("X-User-Id","alice").header("X-Workspace-Id","team-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(post("/api/documents/{id}/restore",id).header("X-User-Id","bob").header("X-Workspace-Id","team-a")).andExpect(status().isNotFound());
        mvc.perform(post("/api/documents/{id}/restore",id).header("X-User-Id","alice").header("X-Workspace-Id","team-a")).andExpect(status().isOk());
        doReturn(Map.entry(2,"doc-hash")).when(rag).ingest(anyString(),anyString(),anyString());
        mvc.perform(post("/api/documents/{id}/reindex",id).header("X-User-Id","alice").header("X-Workspace-Id","team-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.index_status").value("indexed"));
        mvc.perform(get("/api/documents/{id}/versions",id).header("X-User-Id","alice").header("X-Workspace-Id","team-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        assertArrayEquals(original,mvc.perform(get("/api/documents/{id}/original",id).header("X-User-Id","alice").header("X-Workspace-Id","team-a"))
                .andReturn().getResponse().getContentAsByteArray());
    }

    @Test void staleAutosaveReturnsConflictWithoutReplacingTheNewerVersion() throws Exception {
        String response=mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"周报\",\"content_md\":\"第一版\",\"draft\":true,\"metadata\":{\"owner_key\":\"forged\",\"original_attachment\":\"forged\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.document.status").value("draft"))
                .andExpect(jsonPath("$.version.metadata.owner_key").value("default:default"))
                .andExpect(jsonPath("$.version.metadata.original_attachment").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(response).path("document").path("id").asText();
        mvc.perform(put("/api/documents/{id}",id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content_md\":\"第二版\",\"base_version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version.version").value(2));
        mvc.perform(put("/api/documents/{id}",id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content_md\":\"过期草稿\",\"base_version\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENT_VERSION_CONFLICT"));
        mvc.perform(get("/api/documents/{id}",id)).andExpect(jsonPath("$.version.contentMd").value("第二版"));
    }

    @Test void selectedDocumentScopeIsBoundValidatedAndTenantSafe() throws Exception {
        doReturn(Map.entry(3,"selected-hash")).when(rag).ingest(anyString(),anyString(),anyString());
        String response=mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id","alice").header("X-Workspace-Id","team-a")
                .content("{\"title\":\"项目纪要\",\"content_md\":\"完成率 75%\",\"ingest_to_rag\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(response).path("document").path("id").asText();

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id","alice").header("X-Workspace-Id","team-a")
                .content("{\"message\":\"总结附件\",\"session_id\":\"s1\",\"knowledge_scope\":\"selected\",\"document_ids\":[\""+id+"\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task_id").isNotEmpty());
        var captor=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatApplication).process(captor.capture(),any(AtomicBoolean.class));
        assertEquals("selected",captor.getValue().getKnowledgeScope());
        assertEquals(java.util.Set.of("selected-hash"),captor.getValue().getAllowedDocHashes());
        assertEquals(java.util.List.of(id),captor.getValue().getDocumentIds());
        assertTrue(captor.getValue().isUseRag());

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id","bob").header("X-Workspace-Id","team-a")
                .content("{\"message\":\"越权读取\",\"session_id\":\"s2\",\"document_ids\":[\""+id+"\"]}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));

        String unindexed=mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id","alice").header("X-Workspace-Id","team-a")
                .content("{\"title\":\"未索引草稿\",\"content_md\":\"草稿正文\",\"draft\":true}"))
                .andReturn().getResponse().getContentAsString();
        String unindexedId=mapper.readTree(unindexed).path("document").path("id").asText();
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id","alice").header("X-Workspace-Id","team-a")
                .content("{\"message\":\"总结草稿\",\"session_id\":\"s3\",\"document_ids\":[\""+unindexedId+"\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENT_NOT_INDEXED"));
    }
}
