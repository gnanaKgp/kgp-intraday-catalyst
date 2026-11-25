package com.catalyst.terminal.repository;

import com.catalyst.terminal.model.CatalystFilingRecord;
import com.catalyst.terminal.model.Direction;
import com.catalyst.terminal.model.FilingCategory;
import com.catalyst.terminal.model.SignalTier;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Repository
public class CatalystFilingRepository {

    private static final Logger log = LoggerFactory.getLogger(CatalystFilingRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public CatalystFilingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS catalyst_filings (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                filing_uid VARCHAR(512) NOT NULL,
                symbol VARCHAR(32) NOT NULL,
                company_name VARCHAR(255) NOT NULL,
                raw_headline VARCHAR(1024),
                attachment_url VARCHAR(1024),
                direction VARCHAR(16) NOT NULL,
                catalyst_score INT NOT NULL,
                tier VARCHAR(16) NOT NULL,
                category VARCHAR(32) NOT NULL,
                order_value_cr DOUBLE,
                order_to_mcap_pct DOUBLE,
                is_actionable_intraday BOOLEAN NOT NULL,
                headline_summary VARCHAR(512),
                institutional_rationale VARCHAR(2000),
                key_risk VARCHAR(512),
                market_cap_cr DOUBLE,
                ltp DOUBLE,
                day_change_pct DOUBLE,
                watchlist_hit BOOLEAN NOT NULL,
                source_mode VARCHAR(32) NOT NULL,
                evaluated_at TIMESTAMP NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_cf_evaluated_at ON catalyst_filings(evaluated_at);
            CREATE INDEX IF NOT EXISTS idx_cf_score ON catalyst_filings(catalyst_score);
            CREATE INDEX IF NOT EXISTS idx_cf_symbol ON catalyst_filings(symbol);
        """);
        jdbcTemplate.execute("""
            DELETE FROM catalyst_filings
            WHERE id NOT IN (SELECT MIN(id) FROM catalyst_filings GROUP BY filing_uid)
        """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_cf_filing_uid ON catalyst_filings(filing_uid)");
        log.info("H2 catalyst_filings schema initialized. Existing records: {}", countAll());
    }

    public boolean existsByFilingUid(String filingUid) {
        if (filingUid == null || filingUid.isBlank()) return false;
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM catalyst_filings WHERE filing_uid = ?",
                Integer.class,
                truncate(filingUid, 512)
        );
        return cnt != null && cnt > 0;
    }

    public CatalystFilingRecord save(CatalystFilingRecord r) {
        if (r.filingUid() != null && existsByFilingUid(r.filingUid())) {
            List<CatalystFilingRecord> existing = jdbcTemplate.query(
                    "SELECT * FROM catalyst_filings WHERE filing_uid = ? LIMIT 1",
                    new FilingRowMapper(),
                    truncate(r.filingUid(), 512)
            );
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
        }
        String sql = """
            INSERT INTO catalyst_filings (
                filing_uid, symbol, company_name, raw_headline, attachment_url,
                direction, catalyst_score, tier, category, order_value_cr, order_to_mcap_pct,
                is_actionable_intraday, headline_summary, institutional_rationale, key_risk,
                market_cap_cr, ltp, day_change_pct, watchlist_hit, source_mode, evaluated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        LocalDateTime evalTime = r.evaluatedAt() != null ? r.evaluatedAt() : LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, truncate(r.filingUid(), 512));
            ps.setString(2, truncate(r.symbol(), 32));
            ps.setString(3, truncate(r.companyName(), 255));
            ps.setString(4, truncate(r.rawHeadline(), 1024));
            ps.setString(5, truncate(r.attachmentUrl(), 1024));
            ps.setString(6, r.direction().name());
            ps.setInt(7, r.catalystScore());
            ps.setString(8, r.tier().name());
            ps.setString(9, r.category().name());
            setNullableDouble(ps, 10, r.orderValueCr());
            setNullableDouble(ps, 11, r.orderToMcapPct());
            ps.setBoolean(12, r.isActionableIntraday());
            ps.setString(13, truncate(r.headlineSummary(), 512));
            ps.setString(14, truncate(r.institutionalRationale(), 2000));
            ps.setString(15, truncate(r.keyRisk(), 512));
            setNullableDouble(ps, 16, r.marketCapCr());
            setNullableDouble(ps, 17, r.ltp());
            setNullableDouble(ps, 18, r.dayChangePct());
            ps.setBoolean(19, r.watchlistHit());
            ps.setString(20, truncate(r.sourceMode(), 32));
            ps.setTimestamp(21, Timestamp.valueOf(evalTime));
            return ps;
        }, keyHolder);

        Long id = keyHolder.getKey() != null ? keyHolder.getKey().longValue() : null;
        return new CatalystFilingRecord(
                id, r.filingUid(), r.symbol(), r.companyName(), r.rawHeadline(), r.attachmentUrl(),
                r.direction(), r.catalystScore(), r.tier(), r.category(), r.orderValueCr(), r.orderToMcapPct(),
                r.isActionableIntraday(), r.headlineSummary(), r.institutionalRationale(), r.keyRisk(),
                r.marketCapCr(), r.ltp(), r.dayChangePct(), r.watchlistHit(), r.sourceMode(), evalTime
        );
    }

    public List<CatalystFilingRecord> search(
            LocalDate from,
            LocalDate to,
            String symbol,
            Integer minScore,
            FilingCategory category,
            Direction direction,
            SignalTier tier,
            Boolean watchlistOnly,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("SELECT * FROM catalyst_filings WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (from != null) {
            sql.append(" AND evaluated_at >= ?");
            params.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sql.append(" AND evaluated_at <= ?");
            params.add(Timestamp.valueOf(to.atTime(23, 59, 59)));
        }
        if (symbol != null && !symbol.isBlank()) {
            String pat = "%" + symbol.trim().toUpperCase() + "%";
            sql.append(" AND (UPPER(symbol) LIKE ? OR UPPER(company_name) LIKE ?)");
            params.add(pat);
            params.add(pat);
        }
        if (minScore != null) {
            sql.append(" AND catalyst_score >= ?");
            params.add(minScore);
        }
        if (category != null) {
            sql.append(" AND category = ?");
            params.add(category.name());
        }
        if (direction != null) {
            sql.append(" AND direction = ?");
            params.add(direction.name());
        }
        if (tier != null) {
            sql.append(" AND tier = ?");
            params.add(tier.name());
        }
        if (Boolean.TRUE.equals(watchlistOnly)) {
            sql.append(" AND watchlist_hit = TRUE");
        }

        int safeLimit = Math.max(1, Math.min(limit, 500));
        sql.append(" ORDER BY evaluated_at DESC, id DESC LIMIT ?");
        params.add(safeLimit);

        return jdbcTemplate.query(sql.toString(), new FilingRowMapper(), params.toArray());
    }

    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalyst_filings", Long.class);
        return count != null ? count : 0L;
    }

    public Map<SignalTier, Long> countByTierToday() {
        Map<SignalTier, Long> counts = new EnumMap<>(SignalTier.class);
        for (SignalTier t : SignalTier.values()) {
            counts.put(t, 0L);
        }
        Timestamp startOfDay = Timestamp.valueOf(LocalDate.now().atStartOfDay());
        jdbcTemplate.query(
                "SELECT tier, COUNT(*) AS cnt FROM catalyst_filings WHERE evaluated_at >= ? GROUP BY tier",
                rs -> {
                    String tierName = rs.getString("tier");
                    try {
                        counts.put(SignalTier.valueOf(tierName), rs.getLong("cnt"));
                    } catch (IllegalArgumentException ignored) {}
                },
                startOfDay
        );
        return counts;
    }

    public int purgeOlderThanDays(int days) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        int deleted = jdbcTemplate.update("DELETE FROM catalyst_filings WHERE evaluated_at < ?", Timestamp.valueOf(cutoff));
        if (deleted > 0) {
            log.info("Pruned {} catalyst_filings older than {} days", deleted, days);
        }
        return deleted;
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double val) throws SQLException {
        if (val == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, val);
        }
    }

    private static String truncate(String val, int max) {
        if (val == null) return null;
        return val.length() <= max ? val : val.substring(0, max);
    }

    private static class FilingRowMapper implements RowMapper<CatalystFilingRecord> {
        @Override
        public CatalystFilingRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp evalTs = rs.getTimestamp("evaluated_at");
            return new CatalystFilingRecord(
                    rs.getLong("id"),
                    rs.getString("filing_uid"),
                    rs.getString("symbol"),
                    rs.getString("company_name"),
                    rs.getString("raw_headline"),
                    rs.getString("attachment_url"),
                    Direction.valueOf(rs.getString("direction")),
                    rs.getInt("catalyst_score"),
                    SignalTier.valueOf(rs.getString("tier")),
                    FilingCategory.valueOf(rs.getString("category")),
                    getNullableDouble(rs, "order_value_cr"),
                    getNullableDouble(rs, "order_to_mcap_pct"),
                    rs.getBoolean("is_actionable_intraday"),
                    rs.getString("headline_summary"),
                    rs.getString("institutional_rationale"),
                    rs.getString("key_risk"),
                    getNullableDouble(rs, "market_cap_cr"),
                    getNullableDouble(rs, "ltp"),
                    getNullableDouble(rs, "day_change_pct"),
                    rs.getBoolean("watchlist_hit"),
                    rs.getString("source_mode"),
                    evalTs != null ? evalTs.toLocalDateTime() : LocalDateTime.now()
            );
        }

        private Double getNullableDouble(ResultSet rs, String col) throws SQLException {
            double v = rs.getDouble(col);
            return rs.wasNull() ? null : v;
        }
    }
}

