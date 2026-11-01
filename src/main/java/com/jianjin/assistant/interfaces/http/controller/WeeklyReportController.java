package com.jianjin.assistant.interfaces.http.controller;

import com.jianjin.assistant.domain.document.DocumentVersion;
import com.jianjin.assistant.domain.document.LibraryRepo;
import com.jianjin.assistant.interfaces.http.error.ApiException;
import com.jianjin.assistant.interfaces.http.security.RequestIdentityHolder;
import com.jianjin.assistant.service.document.DocumentLibraryService;
import com.jianjin.assistant.service.office.OfficeExportService;
import com.jianjin.assistant.service.office.ReviewDiffService;
import com.jianjin.assistant.service.office.WeeklyReportService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WeeklyReportController {
    private final WeeklyReportService weeklyReports;
    private final ReviewDiffService diffs;
    private final OfficeExportService exports;
    private final DocumentLibraryService library;

    public WeeklyReportController(WeeklyReportService weeklyReports, ReviewDiffService diffs,
                                  OfficeExportService exports, DocumentLibraryService library) {
        this.weeklyReports = weeklyReports;
        this.diffs = diffs;
        this.exports = exports;
        this.library = library;
    }

    @GetMapping("/weekly-report/templates")
    public List<WeeklyReportService.Template> templates() {
        return weeklyReports.templates(RequestIdentityHolder.current().ownerKey());
    }

    @PostMapping("/weekly-report/templates")
    public WeeklyReportService.Template createTemplate(@RequestBody Map<String, Object> request) {
        try {
            return weeklyReports.createTemplate(RequestIdentityHolder.current().ownerKey(),
                    string(request.get("name")), string(request.get("description")), string(request.get("content_md")));
        } catch (IllegalArgumentException e) {
            throw new ApiException("WEEKLY_REPORT_TEMPLATE_INVALID", e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    @PostMapping("/weekly-report/generate")
    public WeeklyReportService.Result generate(@RequestBody WeeklyReportService.GenerateRequest request) {
        try { return weeklyReports.generate(RequestIdentityHolder.current().ownerKey(), request); }
        catch (IllegalArgumentException e) { throw new ApiException("WEEKLY_REPORT_INVALID", e.getMessage(), HttpStatus.BAD_REQUEST); }
    }

    @PostMapping("/weekly-report/compare")
    public ReviewDiffService.Result compare(@RequestBody Map<String, Object> request) {
        return diffs.compare(string(request.get("original_content")), string(request.get("revised_content")));
    }

    @GetMapping("/documents/{id}/diff")
    public ReviewDiffService.Result documentDiff(@PathVariable String id,
                                                 @RequestParam("from_version") String fromVersion,
                                                 @RequestParam("to_version") String toVersion) {
        ownedDocument(id);
        DocumentVersion left = ownedVersion(id, fromVersion);
        DocumentVersion right = ownedVersion(id, toVersion);
        return diffs.compare(left.getContentMd(), right.getContentMd());
    }

    @GetMapping("/documents/{id}/export")
    public ResponseEntity<ByteArrayResource> export(@PathVariable String id,
                                                    @RequestParam(defaultValue = "docx") String format,
                                                    @RequestParam(value = "version_id", required = false) String versionId) {
        LibraryRepo.DocumentWithVersion current = ownedDocument(id);
        DocumentVersion version = versionId == null || versionId.isBlank() ? current.version : ownedVersion(id, versionId);
        OfficeExportService.Exported exported;
        try { exported = exports.export(current.document.getTitle(), version.getContentMd(), format); }
        catch (IllegalArgumentException e) { throw new ApiException("EXPORT_FORMAT_INVALID", e.getMessage(), HttpStatus.BAD_REQUEST); }
        catch (IllegalStateException e) { throw new ApiException("EXPORT_FAILED", e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
        String safeName = current.document.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeName.isBlank()) safeName = "document";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(exported.contentType()))
                .contentLength(exported.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(safeName + "." + exported.extension(), StandardCharsets.UTF_8).build().toString())
                .body(new ByteArrayResource(exported.bytes()));
    }

    private LibraryRepo.DocumentWithVersion ownedDocument(String id) {
        LibraryRepo.DocumentWithVersion value;
        try { value = library.get(id); }
        catch (IllegalArgumentException e) { throw new ApiException("DOCUMENT_NOT_FOUND", "文档不存在", HttpStatus.NOT_FOUND); }
        if (!owned(value.version)) throw new ApiException("DOCUMENT_NOT_FOUND", "文档不存在", HttpStatus.NOT_FOUND);
        return value;
    }

    private DocumentVersion ownedVersion(String documentId, String versionId) {
        DocumentVersion version;
        try { version = library.repo().getVersion(versionId); }
        catch (IllegalArgumentException e) { throw new ApiException("VERSION_NOT_FOUND", "版本不存在", HttpStatus.NOT_FOUND); }
        if (!documentId.equals(version.getDocumentId()) || !owned(version)) {
            throw new ApiException("VERSION_NOT_FOUND", "版本不存在", HttpStatus.NOT_FOUND);
        }
        return version;
    }

    private boolean owned(DocumentVersion version) {
        String owner = RequestIdentityHolder.current().ownerKey();
        Object actual = version.getMetadata().get("owner_key");
        return owner.equals(String.valueOf(actual)) || (actual == null && "default:default".equals(owner));
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
}
