package com.bankos.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts selectable text per page with PDFBox. Page numbers come from here, never from the model.
 * Scanned PDFs (no text layer) yield empty pages; OCR is intentionally out of scope.
 */
@Service
public class PdfService {

    public record PageText(int pageNumber, String text) {

        public boolean hasText() {
            return text != null && !text.isBlank();
        }
    }

    public List<PageText> extractPages(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return extract(document);
        }
    }

    public List<PageText> extractPages(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return extract(document);
        }
    }

    private List<PageText> extract(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        List<PageText> pages = new ArrayList<>();
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            pages.add(new PageText(page, stripper.getText(document).strip()));
        }
        return pages;
    }
}
