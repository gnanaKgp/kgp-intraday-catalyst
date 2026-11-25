package com.catalyst.terminal.service;

import com.catalyst.terminal.config.AppProperties;
import com.catalyst.terminal.gateway.NseFeedGateway;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PdfExtractorService {

    private static final Logger log = LoggerFactory.getLogger(PdfExtractorService.class);

    private final NseFeedGateway feedGateway;
    private final AppProperties properties;

    public PdfExtractorService(NseFeedGateway feedGateway, AppProperties properties) {
        this.feedGateway = feedGateway;
        this.properties = properties;
    }

    public String extractFromUrl(String attachmentUrl) {
        if (attachmentUrl == null || !attachmentUrl.toLowerCase().endsWith(".pdf")) {
            return "";
        }
        byte[] bytes = feedGateway.downloadAttachmentBytes(attachmentUrl);
        return extractFromBytes(bytes);
    }

    public String extractFromBytes(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return "";
        }
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(Math.max(1, properties.getPdf().getMaxPages()));
            String text = stripper.getText(doc);
            return text != null ? text.trim() : "";
        } catch (Exception e) {
            log.warn("Failed to parse PDF bytes: {}", e.getMessage());
            return "";
        }
    }
}

