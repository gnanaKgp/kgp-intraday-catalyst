package com.catalyst.terminal.controller;

import com.catalyst.terminal.model.*;
import com.catalyst.terminal.repository.CatalystFilingRepository;
import com.catalyst.terminal.service.CatalystEvaluatorService;
import com.catalyst.terminal.service.CsvExportService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CatalystFeedController {

    private final CatalystFilingRepository filingRepository;
    private final CatalystEvaluatorService evaluatorService;
    private final CsvExportService csvExportService;

    public CatalystFeedController(
            CatalystFilingRepository filingRepository,
            CatalystEvaluatorService evaluatorService,
            CsvExportService csvExportService
    ) {
        this.filingRepository = filingRepository;
        this.evaluatorService = evaluatorService;
        this.csvExportService = csvExportService;
    }

    @GetMapping("/filings")
    public ResponseEntity<List<CatalystFilingRecord>> searchFilings(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) FilingCategory category,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) SignalTier tier,
            @RequestParam(required = false) Boolean watchlistOnly,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return ResponseEntity.ok(filingRepository.search(
                from, to, symbol, minScore, category, direction, tier, watchlistOnly, limit
        ));
    }

    @GetMapping("/filings/export")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) FilingCategory category,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) SignalTier tier,
            @RequestParam(required = false) Boolean watchlistOnly
    ) {
        List<CatalystFilingRecord> records = filingRepository.search(
                from, to, symbol, minScore, category, direction, tier, watchlistOnly, 500
        );
        byte[] csv = csvExportService.exportFilings(records);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"catalyst_filings.csv\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(csv);
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeText(@Valid @RequestBody AnalyzeFilingRequest request) {
        try {
            return ResponseEntity.ok(evaluatorService.processManualAnalysis(request, null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/analyze/pdf")
    public ResponseEntity<?> analyzePdf(
            @RequestParam(required = false) String symbol,
            @RequestParam String companyName,
            @RequestParam(required = false) String headline,
            @RequestParam(required = false) Double marketCapCrOverride,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        if (companyName == null || companyName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "companyName is required"));
        }
        AnalyzeFilingRequest req = new AnalyzeFilingRequest(
                symbol, companyName, headline, null, null, marketCapCrOverride
        );
        try {
            return ResponseEntity.ok(evaluatorService.processManualAnalysis(req, file.getBytes()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }
}

