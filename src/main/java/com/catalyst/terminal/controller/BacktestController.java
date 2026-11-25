package com.catalyst.terminal.controller;

import com.catalyst.terminal.model.funnel.BacktestRequest;
import com.catalyst.terminal.model.funnel.BacktestSummary;
import com.catalyst.terminal.service.funnel.BacktestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    private final BacktestService backtestService;

    public BacktestController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @PostMapping("/run")
    public ResponseEntity<BacktestSummary> runBacktest(@RequestBody(required = false) BacktestRequest request) {
        BacktestRequest safeReq = request != null
                ? request
                : new BacktestRequest(null, null, null, null, null, null, null);
        return ResponseEntity.ok(backtestService.runBacktest(safeReq));
    }
}

