package com.jianjin.assistant.interfaces.http.controller;

import com.jianjin.assistant.interfaces.http.error.ApiException;
import com.jianjin.assistant.service.office.WeeklyReportService;
import com.jianjin.assistant.service.workspace.OfficeTaskService;
import com.jianjin.assistant.service.workspace.PilotService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OfficeOperationsController {
    private final OfficeTaskService tasks;
    private final PilotService pilot;
    public OfficeOperationsController(OfficeTaskService tasks, PilotService pilot) { this.tasks = tasks; this.pilot = pilot; }

    @GetMapping("/tasks") public List<OfficeTaskService.Task> tasks() { return tasks.list(); }
    @GetMapping("/tasks/{id}") public OfficeTaskService.Task task(@PathVariable String id) { return call(() -> tasks.get(id)); }
    @PostMapping("/tasks/weekly-report") public OfficeTaskService.Task weeklyReport(@RequestBody WeeklyReportService.GenerateRequest request) { return call(() -> tasks.submitWeeklyReport(request)); }
    @PostMapping("/tasks/connections/{id}/sync") public OfficeTaskService.Task sync(@PathVariable String id) { return call(() -> tasks.submitConnectionSync(id)); }
    @PostMapping("/tasks/{id}/cancel") public OfficeTaskService.Task cancel(@PathVariable String id) { return call(() -> tasks.cancel(id)); }
    @PostMapping("/tasks/{id}/retry") public OfficeTaskService.Task retry(@PathVariable String id) { return call(() -> tasks.retry(id)); }

    @PostMapping("/pilot/events") public Map<String, Object> event(@RequestBody PilotService.EventRequest request) {
        try { pilot.record(request); return Map.of("ok", true); }
        catch (IllegalArgumentException e) { throw new ApiException("PILOT_EVENT_INVALID", e.getMessage(), HttpStatus.BAD_REQUEST); }
    }
    @GetMapping("/pilot/dashboard") public PilotService.Dashboard dashboard() { return pilot.dashboard(); }

    private static <T> T call(java.util.concurrent.Callable<T> action) {
        try { return action.call(); }
        catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("OFFICE_TASK_INVALID", e.getMessage(), HttpStatus.BAD_REQUEST); }
    }
}
