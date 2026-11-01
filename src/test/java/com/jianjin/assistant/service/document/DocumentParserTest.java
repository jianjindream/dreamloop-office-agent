package com.jianjin.assistant.service.document;

import com.jianjin.assistant.domain.document.ParseResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    @Test
    void parsePlainTextNormalisesContent() {
        DocumentParser p = new DocumentParser();
        ParseResult r = p.parseBytes("note.txt", "text/plain",
                "  line1  \r\n\r\n  line2  ".getBytes(StandardCharsets.UTF_8));
        assertEquals("plain_text", r.parser);
        assertEquals("line1\n\nline2", r.content);
    }

    @Test
    void emptyDocumentRejected() {
        DocumentParser p = new DocumentParser();
        assertThrows(IllegalArgumentException.class, () ->
                p.parseBytes("empty.txt", "text/plain", new byte[0]));
    }

    @Test
    void imageOnlyPdfIsRejectedForOcrInsteadOfBeingIndexed() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            pdf = output.toByteArray();
        }

        DocumentParser.PdfNeedsOcrException error = assertThrows(DocumentParser.PdfNeedsOcrException.class,
                () -> new DocumentParser().parseBytes("scan.pdf", "application/pdf", pdf));
        assertTrue(error.getResult().needsOCR);
        assertEquals(1, error.getResult().pages);
    }

    @Test
    void docxPreservesParagraphAndTableLocators() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("项目进展");
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("指标");
            table.getRow(0).getCell(1).setText("结果");
            table.getRow(1).getCell(0).setText("关闭比例");
            table.getRow(1).getCell(1).setText("75%");
            document.write(output); bytes = output.toByteArray();
        }

        ParseResult result = new DocumentParser().parseBytes("report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);
        assertEquals("apache-poi-docx", result.parser);
        assertTrue(result.content.contains("[paragraph 1]"));
        assertTrue(result.content.contains("[table 1]"));
        assertTrue(result.content.contains("| 关闭比例 | 75% |"));
    }

    @Test
    void xlsxEvaluatesFormulaAndKeepsSheetAndRowLocators() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("缺陷统计");
            Row header = sheet.createRow(0); header.createCell(0).setCellValue("计划"); header.createCell(1).setCellValue("关闭"); header.createCell(2).setCellValue("比例");
            Row data = sheet.createRow(1); data.createCell(0).setCellValue(20); data.createCell(1).setCellValue(15);
            Cell formula = data.createCell(2); formula.setCellFormula("B2/A2");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            workbook.write(output); bytes = output.toByteArray();
        }

        ParseResult result = new DocumentParser().parseBytes("metrics.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        assertEquals("apache-poi-xlsx", result.parser);
        assertTrue(result.content.contains("## Sheet: 缺陷统计"));
        assertTrue(result.content.contains("[row 2]"));
        assertTrue(result.content.contains("0.75"));
    }
}
