package com.catalyst.terminal.controller;

import com.catalyst.terminal.model.WatchlistItem;
import com.catalyst.terminal.repository.WatchlistRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistRepository repository;

    public WatchlistController(WatchlistRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<List<WatchlistItem>> getWatchlist() {
        return ResponseEntity.ok(repository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> addOrUpdate(@RequestBody Map<String, String> body) {
        String symbol = body != null ? body.get("symbol") : null;
        if (symbol == null || symbol.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol is required"));
        }
        String notes = body.getOrDefault("notes", "");
        return ResponseEntity.ok(repository.upsert(symbol, notes));
    }

    @DeleteMapping("/{symbol}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable String symbol) {
        boolean deleted = repository.delete(symbol);
        return ResponseEntity.ok(Map.of("symbol", symbol.toUpperCase(), "deleted", deleted));
    }
}

