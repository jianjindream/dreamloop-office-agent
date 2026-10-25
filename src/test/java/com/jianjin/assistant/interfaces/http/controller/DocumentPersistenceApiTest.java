package com.jianjin.assistant.interfaces.http.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.application.chat.*;
import com.jianjin.assistant.config.*;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.infrastructure.persistence.*;
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
    ObjectMapper mapper=new ObjectMapper();

    @BeforeEach void setup() {
        WorkspaceStorageConfig storage=new WorkspaceStorageConfig(); storage.setDirectory(directory.toString());
        AppConfig app=new AppConfig(); database=new WorkspaceDatabase(storage,app); rag=mock(RagService.class);
        var controller=new AgentController(mock(ChatApplicationService.class),mock(UnifiedAgentService.class),mock(InfrastructureService.class),app,
                mock(ToolService.class),new DocumentParser(),new DocumentLibraryService(new JdbcLibraryRepo(database),rag),
                new ChatTaskRegistry(),new UploadProgressService(),new AttachmentStore(storage));
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalApiExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(),
                        new org.springframework.http.converter.ResourceHttpMessageConverter())
                .addFilters(new ApiAuthenticationFilter(app,mapper)).build();
    }
    @AfterEach void close() { database.close(); }

    @Test void originalsAreProtectedAndRecoverableEvenWhenIndexingFails() throws Exception {
        when(rag.ingest(anyString(),anyString())).thenThrow(new IllegalStateException("offline"));
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
        doReturn(Map.entry(2,"doc-hash")).when(rag).ingest(anyString(),anyString());
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
}
