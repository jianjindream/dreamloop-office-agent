package com.jianjin.assistant.service.office;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class OfficeExportService {
    public record Exported(byte[] bytes, String contentType, String extension) {}

    public Exported export(String title, String markdown, String format) {
        if ("docx".equalsIgnoreCase(format)) return new Exported(docx(title, markdown),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        if ("pdf".equalsIgnoreCase(format)) return new Exported(pdf(title, markdown), "application/pdf", "pdf");
        throw new IllegalArgumentException("format 仅支持 docx 或 pdf");
    }

    byte[] docx(String title, String markdown) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            configurePage(document);
            boolean hasTitle = markdownLines(markdown).stream().anyMatch(line -> line.startsWith("# "));
            if (!hasTitle && title != null && !title.isBlank()) paragraph(document, title, "Title", 20, true);
            List<String[]> tableRows = new ArrayList<>();
            for (String raw : markdownLines(markdown)) {
                String line = raw.trim();
                if (line.startsWith("|")) {
                    String[] cells = parseTableRow(line);
                    if (!divider(cells)) tableRows.add(cells);
                    continue;
                }
                flushTable(document, tableRows);
                if (line.isBlank()) continue;
                if (line.startsWith("# ")) {
                    paragraph(document, line.substring(2), "Title", 20, true);
                } else if (line.startsWith("## ")) paragraph(document, line.substring(3), "Heading 1", 15, true);
                else if (line.startsWith("### ")) paragraph(document, line.substring(4), "Heading 2", 13, true);
                else if (line.startsWith("- ")) paragraph(document, line.substring(2), "List Bullet", 11, false);
                else paragraph(document, line, null, 11, false);
            }
            flushTable(document, tableRows);
            document.write(output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Word 导出失败: " + e.getMessage(), e);
        }
    }

    byte[] pdf(String title, String markdown) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDFont font = loadFont(document);
            PdfWriter writer = new PdfWriter(document, font);
            boolean hasTitle = markdownLines(markdown).stream().anyMatch(line -> line.startsWith("# "));
            if (!hasTitle && title != null && !title.isBlank()) writer.write(title, 20, 28);
            for (String raw : markdownLines(markdown)) {
                String line = raw.trim();
                if (line.isBlank() || divider(parseTableRow(line))) { writer.gap(5); continue; }
                if (line.startsWith("# ")) writer.write(line.substring(2), 20, 28);
                else if (line.startsWith("## ")) writer.write(line.substring(3), 15, 24);
                else if (line.startsWith("### ")) writer.write(line.substring(4), 13, 21);
                else if (line.startsWith("|")) writer.write(String.join("   ", parseTableRow(line)), 9, 15);
                else writer.write(line, 10.5f, 17);
            }
            writer.close();
            document.save(output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PDF 导出失败: " + e.getMessage(), e);
        }
    }

    private static void configurePage(XWPFDocument document) {
        CTPageSz size = document.getDocument().getBody().addNewSectPr().addNewPgSz();
        size.setW(java.math.BigInteger.valueOf(12240));
        size.setH(java.math.BigInteger.valueOf(15840));
        CTPageMar margin = document.getDocument().getBody().getSectPr().addNewPgMar();
        margin.setTop(java.math.BigInteger.valueOf(1080)); margin.setBottom(java.math.BigInteger.valueOf(1080));
        margin.setLeft(java.math.BigInteger.valueOf(1080)); margin.setRight(java.math.BigInteger.valueOf(1080));
    }

    private static void paragraph(XWPFDocument document, String text, String style, int fontSize, boolean bold) {
        XWPFParagraph paragraph = document.createParagraph();
        if (style != null) paragraph.setStyle(style);
        paragraph.setSpacingAfter(style != null && style.startsWith("Heading") ? 100 : 80);
        XWPFRun run = paragraph.createRun(); run.setText(text); run.setFontSize(fontSize); run.setBold(bold); run.setColor("000000");
        run.setFontFamily("Microsoft YaHei");
    }

    private static void flushTable(XWPFDocument document, List<String[]> rows) {
        if (rows.isEmpty()) return;
        int columns = rows.stream().mapToInt(value -> value.length).max().orElse(1);
        XWPFTable table = document.createTable(rows.size(), columns);
        table.setWidth("100%");
        borders(table);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            XWPFTableRow row = table.getRow(rowIndex);
            String[] values = rows.get(rowIndex);
            for (int column = 0; column < columns; column++) {
                XWPFTableCell cell = row.getCell(column);
                cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
                XWPFParagraph paragraph = cell.getParagraphs().get(0);
                paragraph.setAlignment(column == 0 ? ParagraphAlignment.LEFT : ParagraphAlignment.CENTER);
                XWPFRun run = paragraph.createRun(); run.setText(column < values.length ? values[column] : "");
                run.setFontSize(10); run.setFontFamily("Microsoft YaHei"); run.setColor("000000"); run.setBold(rowIndex == 0);
                if (rowIndex == 0) cell.setColor("D9EAF7");
            }
        }
        rows.clear();
    }

    private static void borders(XWPFTable table) {
        CTTblPr props = table.getCTTbl().getTblPr();
        CTTblBorders borders = props.isSetTblBorders() ? props.getTblBorders() : props.addNewTblBorders();
        for (CTBorder border : List.of(borders.addNewTop(), borders.addNewLeft(), borders.addNewBottom(),
                borders.addNewRight(), borders.addNewInsideH(), borders.addNewInsideV())) {
            border.setVal(STBorder.SINGLE); border.setColor("D9D9D9"); border.setSz(java.math.BigInteger.valueOf(4));
        }
    }

    private static List<String> markdownLines(String markdown) {
        return List.of((markdown == null ? "" : markdown).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    private static String[] parseTableRow(String line) {
        if (line == null || !line.trim().startsWith("|")) return new String[0];
        String value = line.trim();
        if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
        if (value.startsWith("|")) value = value.substring(1);
        String[] cells = value.split("(?<!\\\\)\\|", -1);
        for (int i = 0; i < cells.length; i++) cells[i] = cells[i].trim().replace("\\|", "|");
        return cells;
    }

    private static boolean divider(String[] cells) {
        return cells.length > 0 && java.util.Arrays.stream(cells).allMatch(value -> value.matches(":?-{3,}:?"));
    }

    private static PDFont loadFont(PDDocument document) throws IOException {
        List<Path> candidates = List.of(
                Path.of("C:/Windows/Fonts/simhei.ttf"),
                Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf"),
                Path.of("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"));
        for (Path candidate : candidates) if (Files.isRegularFile(candidate)) {
            try (InputStream input = Files.newInputStream(candidate)) { return PDType0Font.load(document, input); }
            catch (IOException ignored) { }
        }
        throw new IOException("未找到中文字体，请安装 Noto Sans CJK 或配置运行镜像字体");
    }

    private static final class PdfWriter implements AutoCloseable {
        private final PDDocument document;
        private final PDFont font;
        private PDPage page;
        private PDPageContentStream stream;
        private float y;
        private static final float LEFT = 54;
        private static final float WIDTH = PDRectangle.LETTER.getWidth() - 108;

        PdfWriter(PDDocument document, PDFont font) throws IOException { this.document = document; this.font = font; newPage(); }

        void write(String text, float size, float leading) throws IOException {
            for (String line : wrap(text, size)) {
                if (y < 60) newPage();
                stream.beginText(); stream.setFont(font, size); stream.newLineAtOffset(LEFT, y); stream.showText(line); stream.endText();
                y -= leading;
            }
            y -= size >= 13 ? 5 : 2;
        }

        void gap(float value) { y -= value; }

        private List<String> wrap(String text, float size) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (int offset = 0; offset < text.length();) {
                int codePoint = text.codePointAt(offset);
                String next = new String(Character.toChars(codePoint));
                String candidate = current + next;
                if (!current.isEmpty() && font.getStringWidth(candidate) / 1000 * size > WIDTH) {
                    lines.add(current.toString()); current.setLength(0);
                }
                current.append(next); offset += Character.charCount(codePoint);
            }
            if (!current.isEmpty()) lines.add(current.toString());
            if (lines.isEmpty()) lines.add("");
            return lines;
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            page = new PDPage(PDRectangle.LETTER); document.addPage(page);
            stream = new PDPageContentStream(document, page); y = PDRectangle.LETTER.getHeight() - 54;
        }

        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }
}
