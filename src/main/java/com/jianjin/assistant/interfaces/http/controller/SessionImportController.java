package com.jianjin.assistant.interfaces.http.controller;

import com.jianjin.assistant.infrastructure.persistence.ChatHistoryRepository;
import com.jianjin.assistant.interfaces.http.security.RequestIdentityHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class SessionImportController {
    private final ChatHistoryRepository history;
    public SessionImportController(ChatHistoryRepository history) { this.history=history; }
    public record ImportRequest(String id,String title,long createdAt,List<ChatHistoryRepository.ImportedMessage> messages) {}

    @PostMapping("/import")
    public Map<String,Object> importSession(@RequestBody ImportRequest request) {
        boolean imported=history.importSession(RequestIdentityHolder.current().ownerKey(),request.id(),request.title(),request.createdAt(),request.messages());
        return Map.of("ok",true,"imported",imported,"session_id",request.id());
    }
}
