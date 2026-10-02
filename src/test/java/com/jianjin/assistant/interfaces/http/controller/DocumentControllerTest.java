package com.jianjin.assistant.interfaces.http.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.application.chat.ChatApplicationService;
import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.infrastructure.InfrastructureService;
import com.jianjin.assistant.service.agent.UnifiedAgentService;
import com.jianjin.assistant.service.document.DocumentLibraryService;
import com.jianjin.assistant.service.document.DocumentParser;
import com.jianjin.assistant.service.document.InMemoryLibraryRepo;
import com.jianjin.assistant.service.document.UploadProgressService;
import com.jianjin.assistant.application.chat.ChatTaskRegistry;
import com.jianjin.assistant.interfaces.http.error.GlobalApiExceptionHandler;
import com.jianjin.assistant.service.rag.RagService;
import com.jianjin.assistant.service.tools.ToolService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(
            mock(ChatApplicationService.class),
            mock(UnifiedAgentService.class),
            mock(InfrastructureService.class),
            mock(AppConfig.class),
            mock(ToolService.class),
            mock(DocumentParser.class),
            new DocumentLibraryService(new InMemoryLibraryRepo(), mock(RagService.class)),
            new ChatTaskRegistry(),
            new UploadProgressService()
    )).setControllerAdvice(new GlobalApiExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();

    @Test
    void createUpdateAndReadVersions() throws Exception {
        String createdJson = mvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"会议纪要\",\"content_md\":\"第一版\",\"metadata\":{\"chatSessionId\":\"session-1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.version").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = mapper.readTree(createdJson);
        String id = created.path("document").path("id").asText();

        mvc.perform(put("/api/documents/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"会议纪要（修订）\",\"content_md\":\"第二版\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.version.version").value(2))
                .andExpect(jsonPath("$.version.metadata.chatSessionId").value("session-1"));

        mvc.perform(get("/api/documents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.contentMd").value("第二版"));
        mvc.perform(get("/api/documents/{id}/versions", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].contentMd").value("第一版"));
    }

    @Test
    void updatingUnknownDocumentReturnsNotFound() throws Exception {
        mvc.perform(put("/api/documents/missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"无效\",\"content_md\":\"正文\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void deletesDocumentById() throws Exception {
        String createdJson = mvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"临时文档\",\"content_md\":\"正文\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(createdJson).path("document").path("id").asText();
        mvc.perform(delete("/api/documents/{id}", id)).andExpect(status().isOk());
        mvc.perform(get("/api/documents/{id}", id)).andExpect(status().isNotFound());
    }
}
