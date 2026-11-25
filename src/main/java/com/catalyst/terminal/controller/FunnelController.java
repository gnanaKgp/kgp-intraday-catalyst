package com.catalyst.terminal.controller;

import com.catalyst.terminal.config.FunnelProperties;
import com.catalyst.terminal.repository.FunnelSetupRepository;
import com.catalyst.terminal.service.dhan.DhanAuthService;
import com.catalyst.terminal.service.funnel.DailyFunnelOrchestrator;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/funnel")
public class FunnelController {

    private final DailyFunnelOrchestrator orchestrator;
    private final FunnelProperties funnelProperties;
    private final DhanAuthService dhanAuthService;
    private final FunnelSetupRepository funnelSetupRepository;

    public FunnelController(
            DailyFunnelOrchestrator orchestrator,
            FunnelProperties funnelProperties,
            DhanAuthService dhanAuthService,
            FunnelSetupRepository funnelSetupRepository
    ) {
        this.orchestrator = orchestrator;
        this.funnelProperties = funnelProperties;
        this.dhanAuthService = dhanAuthService;
        this.funnelSetupRepository = funnelSetupRepository;
    }

    @GetMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> getSnapshot() {
        return ResponseEntity.ok(orchestrator.getSnapshot());
    }

    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "50") int limit
    ) {
        if (date != null) {
            return ResponseEntity.ok(funnelSetupRepository.findByDate(date));
        }
        return ResponseEntity.ok(funnelSetupRepository.findRecent(limit));
    }

    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> triggerScan(
            @RequestParam(required = false) Double capitalInr,
            @RequestParam(required = false) Double riskPct
    ) {
        if (capitalInr != null && capitalInr >= 1000) {
            funnelProperties.setCapitalInr(capitalInr);
        }
        if (riskPct != null && riskPct > 0 && riskPct <= 10.0) {
            funnelProperties.setRiskPerTradePct(riskPct);
        }
        return ResponseEntity.ok(orchestrator.runFullRankScan(true));
    }

    @PostMapping("/dhan/refresh")
    public ResponseEntity<Map<String, Object>> refreshDhanAuth() {
        if (!dhanAuthService.isConfigured()) {
            return ResponseEntity.ok(Map.of(
                    "configured", false,
                    "authenticated", false,
                    "status", "DHAN_NOT_CONFIGURED"
            ));
        }
        Optional<String> token = dhanAuthService.getValidAccessToken();
        return ResponseEntity.ok(Map.of(
                "configured", true,
                "authenticated", token.isPresent(),
                "status", dhanAuthService.getLastAuthStatus()
        ));
    }
}

