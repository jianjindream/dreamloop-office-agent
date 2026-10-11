package com.jianjin.assistant.service.document;

import com.jianjin.assistant.domain.document.ParseResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
}
