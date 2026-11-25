package com.catalyst.terminal.repository;

import com.catalyst.terminal.model.WatchlistItem;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class WatchlistRepository {

    private final JdbcTemplate jdbcTemplate;

    public WatchlistRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS watchlist_items (
                symbol VARCHAR(32) PRIMARY KEY,
                notes VARCHAR(255),
                added_at TIMESTAMP NOT NULL
            )
        """);
    }

    public List<WatchlistItem> findAll() {
        return jdbcTemplate.query(
                "SELECT symbol, notes, added_at FROM watchlist_items ORDER BY added_at DESC",
                (rs, rowNum) -> {
                    Timestamp ts = rs.getTimestamp("added_at");
                    return new WatchlistItem(
                            rs.getString("symbol"),
                            rs.getString("notes"),
                            ts != null ? ts.toLocalDateTime() : LocalDateTime.now()
                    );
                }
        );
    }

    public Set<String> findAllSymbols() {
        return findAll().stream()
                .map(item -> item.symbol().toUpperCase())
                .collect(Collectors.toSet());
    }

    public boolean exists(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watchlist_items WHERE UPPER(symbol) = ?",
                Integer.class,
                symbol.trim().toUpperCase()
        );
        return cnt != null && cnt > 0;
    }

    public WatchlistItem upsert(String symbol, String notes) {
        String clean = symbol.trim().toUpperCase();
        String safeNotes = notes != null ? notes.trim() : "";
        LocalDateTime now = LocalDateTime.now();
        if (exists(clean)) {
            jdbcTemplate.update("UPDATE watchlist_items SET notes = ? WHERE symbol = ?", safeNotes, clean);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO watchlist_items (symbol, notes, added_at) VALUES (?, ?, ?)",
                    clean, safeNotes, Timestamp.valueOf(now)
            );
        }
        return new WatchlistItem(clean, safeNotes, now);
    }

    public boolean delete(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        int rows = jdbcTemplate.update("DELETE FROM watchlist_items WHERE UPPER(symbol) = ?", symbol.trim().toUpperCase());
        return rows > 0;
    }
}

