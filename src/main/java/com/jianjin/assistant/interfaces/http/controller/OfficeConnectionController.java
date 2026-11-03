package com.jianjin.assistant.interfaces.http.controller;

import com.jianjin.assistant.interfaces.http.error.ApiException;
import com.jianjin.assistant.service.workspace.OfficeConnectionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/connections")
public class OfficeConnectionController {
    private final OfficeConnectionService connections;
    public OfficeConnectionController(OfficeConnectionService connections) { this.connections = connections; }

    @GetMapping("/providers") public List<OfficeConnectionService.Provider> providers() { return connections.providers(); }
    @GetMapping public List<OfficeConnectionService.Connection> list() { return connections.list(); }

    @PostMapping public OfficeConnectionService.Connection create(@RequestBody Map<String, Object> request) {
        try { return connections.create(text(request, "provider"), text(request, "display_name"), text(request, "credential_ref"), map(request.get("config"))); }
        catch (IllegalArgumentException e) { throw invalid(e); }
    }

    @PatchMapping("/{id}") public OfficeConnectionService.Connection update(@PathVariable String id, @RequestBody Map<String, Object> request) {
        try { return connections.update(id, text(request, "display_name"), text(request, "credential_ref"), request.containsKey("config") ? map(request.get("config")) : null); }
        catch (IllegalArgumentException e) { throw invalid(e); }
    }

    @PostMapping("/{id}/test") public OfficeConnectionService.TestResult test(@PathVariable String id) {
        try { return connections.test(id); } catch (IllegalArgumentException e) { throw invalid(e); }
    }

    @DeleteMapping("/{id}") public Map<String, Object> delete(@PathVariable String id) {
        try { connections.delete(id); return Map.of("ok", true, "connection_id", id); }
        catch (IllegalArgumentException e) { throw invalid(e); }
    }

    private static String text(Map<String, Object> request, String key) { return request.get(key) == null ? "" : String.valueOf(request.get(key)); }
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return new java.util.LinkedHashMap<>();
        Map<String, Object> result = new java.util.LinkedHashMap<>(); raw.forEach((key, item) -> result.put(String.valueOf(key), item)); return result;
    }
    private static ApiException invalid(IllegalArgumentException error) { return new ApiException("CONNECTION_INVALID", error.getMessage(), HttpStatus.BAD_REQUEST); }
}
