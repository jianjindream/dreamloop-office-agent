package com.jianjin.assistant.interfaces.http.controller;

import com.jianjin.assistant.interfaces.http.error.ApiException;
import com.jianjin.assistant.service.workspace.WorkspaceAccessService;
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
@RequestMapping("/api/team")
public class WorkspaceTeamController {
    private final WorkspaceAccessService access;
    public WorkspaceTeamController(WorkspaceAccessService access) { this.access = access; }

    @GetMapping("/me") public WorkspaceAccessService.Member me() { return access.current(); }
    @GetMapping("/members") public List<WorkspaceAccessService.Member> members() { return access.list(); }

    @PostMapping("/members")
    public WorkspaceAccessService.Member invite(@RequestBody Map<String, Object> request) {
        try { return access.invite(text(request, "user_id"), text(request, "display_name"), text(request, "email"), text(request, "role")); }
        catch (IllegalArgumentException e) { throw invalid(e); }
    }

    @PatchMapping("/members/{userId}")
    public WorkspaceAccessService.Member update(@PathVariable String userId, @RequestBody Map<String, Object> request) {
        try { return access.update(userId, text(request, "role"), text(request, "status")); }
        catch (IllegalArgumentException e) { throw invalid(e); }
    }

    @DeleteMapping("/members/{userId}")
    public Map<String, Object> remove(@PathVariable String userId) {
        try { access.remove(userId); return Map.of("ok", true, "user_id", userId); }
        catch (IllegalArgumentException e) { throw invalid(e); }
    }

    private static String text(Map<String, Object> request, String key) { return request.get(key) == null ? "" : String.valueOf(request.get(key)); }
    private static ApiException invalid(IllegalArgumentException error) { return new ApiException("TEAM_MEMBER_INVALID", error.getMessage(), HttpStatus.BAD_REQUEST); }
}
