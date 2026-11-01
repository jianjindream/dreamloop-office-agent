package com.jianjin.assistant.service.office;

import com.jianjin.assistant.domain.document.Document;
import com.jianjin.assistant.domain.document.DocumentVersion;
import com.jianjin.assistant.domain.document.LibraryRepo;
import com.jianjin.assistant.service.document.DocumentLibraryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeeklyReportServiceTest {
    @Test
    void generatesPeriodBoundReportWithDeterministicMetric() {
        DocumentLibraryService library = mock(DocumentLibraryService.class);
        LibraryRepo.DocumentWithVersion source = value("doc-source", "本期项目记录", """
                登录页于 10 月 16 日完成，负责人林澄。
                登录回归测试于 10 月 20 日完成，负责人林澄。资料下载功能于 10 月 22 日完成开发并通过验收，负责人周禾。
                本期计划处理 20 个缺陷，已关闭 15 个，其中 3 个为历史缺陷；其余 5 个仍处理中。
                """, "owner");
        when(library.get("doc-source")).thenReturn(source);
        when(library.writeDocument(any(), eq(false))).thenReturn(saved());

        WeeklyReportService.Result result = new WeeklyReportService(library).generate("owner",
                new WeeklyReportService.GenerateRequest("星桥客户门户", LocalDate.of(2026, 10, 19),
                        LocalDate.of(2026, 10, 23), WeeklyReportService.DEFAULT_TEMPLATE_ID,
                        List.of("doc-source"), "", true));

        assertTrue(result.contentMd().contains("10 月 20 日完成"));
        assertFalse(result.contentMd().contains("- 登录页于 10 月 16 日完成"));
        assertTrue(result.contentMd().contains("关闭比例 75%"));
        assertEquals("0.7500", result.review().calculations().get(0).result().toPlainString());
        assertEquals("5", result.review().calculations().get(1).result().toPlainString());
    }

    @Test
    void surfacesConflictingFactsWithoutChoosingOne() {
        DocumentLibraryService library = mock(DocumentLibraryService.class);
        when(library.get("one")).thenReturn(value("one", "进度记录", "本期缺陷关闭 12 个，总计划缺陷 20 个；通知联调负责人为林澄，计划 10 月 28 日完成。", "owner"));
        when(library.get("two")).thenReturn(value("two", "会议纪要", "本期缺陷关闭 15 个。通知联调负责人为周禾，计划 10 月 29 日完成。", "owner"));
        when(library.writeDocument(any(), eq(false))).thenReturn(saved());

        WeeklyReportService.Result result = new WeeklyReportService(library).generate("owner",
                new WeeklyReportService.GenerateRequest("星桥客户门户", LocalDate.of(2026, 10, 19),
                        LocalDate.of(2026, 10, 23), WeeklyReportService.DEFAULT_TEMPLATE_ID,
                        List.of("one", "two"), "", true));

        assertTrue(result.review().conflicts().stream().anyMatch(issue -> issue.field().equals("defects_closed")));
        assertTrue(result.review().conflicts().stream().anyMatch(issue -> issue.field().equals("notification_owner")));
        assertTrue(result.contentMd().contains("12"));
        assertTrue(result.contentMd().contains("15"));
    }

    private static LibraryRepo.DocumentWithVersion value(String id, String title, String content, String owner) {
        Document document = new Document(); document.setId(id); document.setTitle(title); document.setDocType("upload");
        document.setStatus(Document.STATUS_ACTIVE); document.setLatestVersion(1); document.setLatestVersionId("ver-" + id);
        DocumentVersion version = new DocumentVersion(); version.setId("ver-" + id); version.setDocumentId(id);
        version.setVersion(1); version.setContentMd(content); version.setCreatedAt(Instant.parse("2026-10-23T10:00:00Z"));
        version.setMetadata(new LinkedHashMap<>(java.util.Map.of("owner_key", owner)));
        return new LibraryRepo.DocumentWithVersion(document, version);
    }

    private static DocumentLibraryService.Result saved() {
        LibraryRepo.DocumentWithVersion value = value("report", "周报", "body", "owner");
        return new DocumentLibraryService.Result(value.document, value.version, true);
    }
}
