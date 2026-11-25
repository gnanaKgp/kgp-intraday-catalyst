package com.catalyst.terminal.service;

import com.catalyst.terminal.model.CatalystFilingRecord;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class CsvExportService {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public byte[] exportFilings(List<CatalystFilingRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("Evaluated At,Symbol,Company Name,Tier,Score,Direction,Category,Actionable Intraday,Order Value (Cr),Market Cap (Cr),Order/MCap %,LTP,Day Change %,Watchlist,Headline Summary,Institutional Rationale,Key Risk,Attachment URL\n");

        for (CatalystFilingRecord r : records) {
            sb.append(r.evaluatedAt() != null ? r.evaluatedAt().format(TS_FMT) : "").append(',')
                    .append(esc(r.symbol())).append(',')
                    .append(esc(r.companyName())).append(',')
                    .append(r.tier().name()).append(',')
                    .append(r.catalystScore()).append(',')
                    .append(r.direction().name()).append(',')
                    .append(r.category().name()).append(',')
                    .append(r.isActionableIntraday()).append(',')
                    .append(fmt(r.orderValueCr())).append(',')
                    .append(fmt(r.marketCapCr())).append(',')
                    .append(fmt(r.orderToMcapPct())).append(',')
                    .append(fmt(r.ltp())).append(',')
                    .append(fmt(r.dayChangePct())).append(',')
                    .append(r.watchlistHit()).append(',')
                    .append(esc(r.headlineSummary())).append(',')
                    .append(esc(r.institutionalRationale())).append(',')
                    .append(esc(r.keyRisk())).append(',')
                    .append(esc(r.attachmentUrl()))
                    .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String fmt(Double val) {
        return val == null ? "" : String.format("%.2f", val);
    }

    private static String esc(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n") || val.contains("\r")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}

