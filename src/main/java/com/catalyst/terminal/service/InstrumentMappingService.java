package com.catalyst.terminal.service;

import com.catalyst.terminal.gateway.InstrumentMasterGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class InstrumentMappingService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentMappingService.class);

    private final InstrumentMasterGateway gateway;
    private final Map<String, String> symbolMap = new ConcurrentHashMap<>();
    private final Map<String, String> companyToSymbolMap = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public InstrumentMappingService(InstrumentMasterGateway gateway) {
        this.gateway = gateway;
        seedFallbackSymbols();
    }

    private void seedFallbackSymbols() {
        for (String sym : new String[]{"BDL", "BEL", "RVNL", "BONDADA", "TCS", "INFY", "LT", "SUZLON", "TROM", "RAILTEL", "KPIGREEN"}) {
            symbolMap.put(sym, sym);
        }
    }

    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshInstruments() {
        Optional<String> csvOpt = gateway.downloadInstrumentsCsv();
        if (csvOpt.isEmpty()) {
            log.debug("Instrument master refresh skipped (offline or unavailable); retaining {} symbols.", symbolMap.size());
            return;
        }
        String[] lines = csvOpt.get().split("\n");
        int count = 0;
        for (int i = 1; i < lines.length; i++) {
            String[] cols = lines[i].split(",");
            if (cols.length > 11) {
                String symbol = cols[2].replace("\"", "").trim().toUpperCase();
                String name = cols[3].replace("\"", "").trim().toUpperCase();
                String lotSize = cols[8].replace("\"", "").trim();
                String type = cols[9].replace("\"", "").trim();
                String exchange = cols[11].replace("\"", "").trim();

                if (("NSE".equals(exchange) || "BSE".equals(exchange))
                        && "EQ".equals(type)
                        && "1".equals(lotSize)
                        && !symbol.endsWith("-BE")
                        && !symbol.endsWith("-BZ")
                        && !symbol.endsWith("-SM")) {
                    symbolMap.put(symbol, symbol);
                    if (!name.isBlank()) {
                        companyToSymbolMap.put(name, symbol);
                    }
                    count++;
                }
            }
        }
        log.info("Loaded {} regular EQ instruments ({} company names).", count, companyToSymbolMap.size());
    }

    public void ensureLoadedLazily() {
        if (initialized.compareAndSet(false, true)) {
            refreshInstruments();
        }
    }

    public boolean isKnownRegularSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        ensureLoadedLazily();
        return symbolMap.containsKey(symbol.trim().toUpperCase());
    }

    public String resolveSymbol(String explicitSymbol, String companyName) {
        ensureLoadedLazily();
        if (explicitSymbol != null && !explicitSymbol.isBlank()) {
            String clean = explicitSymbol.trim().toUpperCase();
            if (symbolMap.containsKey(clean)) {
                return clean;
            }
        }
        if (companyName != null && !companyName.isBlank()) {
            String upper = companyName.trim().toUpperCase();
            if (companyToSymbolMap.containsKey(upper)) {
                return companyToSymbolMap.get(upper);
            }
            String stripped = upper
                    .replaceAll("\\b(LIMITED|LTD|INDIA|ENTERPRISES|INDUSTRIES|CORP|CORPORATION|PRIVATE|PVT)\\b", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (companyToSymbolMap.containsKey(stripped)) {
                return companyToSymbolMap.get(stripped);
            }
            String firstToken = upper.split("\\s+")[0].replaceAll("[^A-Z0-9&-]", "");
            if (symbolMap.containsKey(firstToken)) {
                return firstToken;
            }
            if (!firstToken.isBlank()) {
                return firstToken;
            }
        }
        return explicitSymbol != null && !explicitSymbol.isBlank() ? explicitSymbol.trim().toUpperCase() : "UNKNOWN";
    }
}

