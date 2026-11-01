package com.jianjin.assistant.service.office;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficeExportServiceTest {
    private static final String REPORT = """
            # 星桥客户门户项目周报

            汇报周期：2026-10-19 至 2026-10-23

            ## 本周摘要
            本期核实完成事项 2 项，风险或阻塞 1 项。

            ## 本周完成
            | 事项 | 结果 | 来源 |
            | --- | --- | --- |
            | 资料下载功能 | 已通过验收 | S1 |
            | 缺陷处理 | 关闭 15 / 计划 20 = 75% | S1 |

            ## 风险与阻塞
            - 邮件通知服务供应商尚未提供测试账号 [S2]

            ## 资料来源与版本
            - [S1] 本期项目记录 · v2
            - [S2] 会议纪要 · v1
            """;

    @Test
    void exportsReadableWordAndPdfGoldenFiles() throws Exception {
        OfficeExportService service = new OfficeExportService();
        byte[] docx = service.docx("星桥客户门户项目周报", REPORT);
        byte[] pdf = service.pdf("星桥客户门户项目周报", REPORT);
        Path output = Path.of("target", "phase-three"); Files.createDirectories(output);
        Files.write(output.resolve("weekly-report.docx"), docx);
        Files.write(output.resolve("weekly-report.pdf"), pdf);

        try (XWPFDocument word = new XWPFDocument(new ByteArrayInputStream(docx))) {
            assertTrue(word.getParagraphs().stream().anyMatch(p -> p.getText().contains("星桥客户门户项目周报")));
            assertTrue(word.getTables().stream().anyMatch(table -> table.getText().contains("75%")));
        }
        try (PDDocument document = PDDocument.load(pdf)) {
            assertTrue(document.getNumberOfPages() >= 1);
            assertTrue(new PDFTextStripper().getText(document).contains("星桥客户门户项目周报"));
        }
    }
}
