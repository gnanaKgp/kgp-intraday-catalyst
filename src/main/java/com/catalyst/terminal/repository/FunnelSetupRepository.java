package com.catalyst.terminal.repository;

import com.catalyst.terminal.model.funnel.ExecutionPlan;
import com.catalyst.terminal.model.funnel.FunnelSetupRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Persists ranked intraday funnel setups for post-market audit and win-rate/R:R review.
 */
@Repository
public class FunnelSetupRepository {

    private static final Logger log = LoggerFactory.getLogger(FunnelSetupRepository.class);
    private final JdbcTemplate jdbcTemplate;

    public FunnelSetupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS funnel_setups (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                session_date DATE NOT NULL,
                symbol VARCHAR(32) NOT NULL,
                company_name VARCHAR(255),
                sector VARCHAR(32),
                side VARCHAR(16) NOT NULL,
                composite_score DOUBLE NOT NULL,
                catalyst_score INT NOT NULL,
                ltp DOUBLE NOT NULL,
                rvol DOUBLE,
                atr14 DOUBLE,
                atr_multiple DOUBLE,
                entry_price DOUBLE,
                stop_loss DOUBLE,
                target_price DOUBLE,
                quantity INT,
                rr_ratio DOUBLE,
                guardrails_passed BOOLEAN NOT NULL,
                disqualification_reasons VARCHAR(1024),
                why_moving VARCHAR(512),
                technical_trigger VARCHAR(512),
                evaluated_at TIMESTAMP NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_fs_session_date ON funnel_setups(session_date);
            CREATE INDEX IF NOT EXISTS idx_fs_symbol ON funnel_setups(symbol);
        """);
        log.info("H2 funnel_setups audit schema initialized.");
    }

    public void saveSnapshot(List<FunnelSetupRecord> setups) {
        if (setups == null || setups.isEmpty()) return;
        for (FunnelSetupRecord s : setups) {
            ExecutionPlan p = s.executionPlan();
            LocalDateTime evalAt = s.evaluatedAt() != null ? s.evaluatedAt() : LocalDateTime.now();
            String reasons = s.guardrails() != null && s.guardrails().disqualificationReasons() != null
                    ? String.join("; ", s.guardrails().disqualificationReasons())
                    : "";
            jdbcTemplate.update("""
                INSERT INTO funnel_setups (
                    session_date, symbol, company_name, sector, side, composite_score, catalyst_score,
                    ltp, rvol, atr14, atr_multiple, entry_price, stop_loss, target_price, quantity, rr_ratio,
                    guardrails_passed, disqualification_reasons, why_moving, technical_trigger, evaluated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                    java.sql.Date.valueOf(evalAt.toLocalDate()),
                    s.symbol(),
                    trunc(s.companyName(), 255),
                    trunc(s.sector(), 32),
                    s.side().name(),
                    s.compositeScore(),
                    s.catalystScore(),
                    s.ltp(),
                    s.rvol(),
                    s.guardrails() != null ? s.guardrails().atr14() : 0.0,
                    s.guardrails() != null ? s.guardrails().atrMultiple() : 0.0,
                    p != null ? p.entry() : null,
                    p != null ? p.stopLoss() : null,
                    p != null ? p.target() : null,
                    p != null ? p.quantity() : null,
                    p != null ? p.rrRatio() : null,
                    s.guardrails() != null && s.guardrails().passed(),
                    trunc(reasons, 1024),
                    trunc(s.whyMoving(), 512),
                    trunc(s.technicalTrigger(), 512),
                    Timestamp.valueOf(evalAt)
            );
        }
    }

    public List<Map<String, Object>> findRecent(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.queryForList(
                "SELECT * FROM funnel_setups ORDER BY evaluated_at DESC, id DESC LIMIT ?",
                safe
        );
    }

    public List<Map<String, Object>> findByDate(LocalDate date) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM funnel_setups WHERE session_date = ? ORDER BY evaluated_at DESC, id DESC",
                java.sql.Date.valueOf(date)
        );
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

