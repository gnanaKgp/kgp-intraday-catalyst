package com.catalyst.terminal.service.dhan;

import com.catalyst.terminal.config.TraderDhanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Loads DhanHQ scrip master (NSE_EQ & IDX_I security IDs) and Nifty 500 liquid universe
 * with industry-to-sectoral-index mapping.
 */
@Service
public class DhanInstrumentService {

    private static final Logger log = LoggerFactory.getLogger(DhanInstrumentService.class);
    private static final String DHAN_SCRIP_MASTER_URL = "https://images.dhan.co/api-data/api-scrip-master.csv";
    private static final String NIFTY500_URL = "https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";

    public static final String NIFTY_50_SEC_ID = "13";

    private final TraderDhanProperties properties;
    private final Supplier<HttpClient> httpClientSupplier;

    private final Map<String, String> symbolToSecId = new ConcurrentHashMap<>();
    private final Map<String, String> secIdToSymbol = new ConcurrentHashMap<>();
    private final Map<String, String> indexToSecId = new ConcurrentHashMap<>();
    private final Set<String> nifty500Symbols = ConcurrentHashMap.newKeySet();
    private final Map<String, String> symbolToIndustry = new ConcurrentHashMap<>();
    private final Map<String, String> companyToSymbol = new ConcurrentHashMap<>();
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    public DhanInstrumentService(TraderDhanProperties properties, Supplier<HttpClient> httpClientSupplier) {
        this.properties = properties;
        this.httpClientSupplier = httpClientSupplier;
        seedDefaults();
    }

    private void seedDefaults() {
        indexToSecId.put("NIFTY", "13");
        indexToSecId.put("NIFTY AUTO", "14");
        indexToSecId.put("BANKNIFTY", "25");
        indexToSecId.put("NIFTY FMCG", "28");
        indexToSecId.put("NIFTYIT", "29");
        indexToSecId.put("NIFTY METAL", "31");
        indexToSecId.put("NIFTY PHARMA", "32");
        indexToSecId.put("NIFTY ENERGY", "42");

        // Fallback core NSE_EQ mappings
        Map<String, String> core = Map.ofEntries(
                Map.entry("RELIANCE", "2885"),
                Map.entry("TCS", "11536"),
                Map.entry("HDFCBANK", "1333"),
                Map.entry("INFY", "1594"),
                Map.entry("ICICIBANK", "4963"),
                Map.entry("SBIN", "3045"),
                Map.entry("LT", "11483"),
                Map.entry("RVNL", "9552"),
                Map.entry("BEL", "383"),
                Map.entry("BDL", "2144"),
                Map.entry("JWL", "20224"),
                Map.entry("POWERINDIA", "18457")
        );
        core.forEach((sym, id) -> {
            symbolToSecId.put(sym, id);
            secIdToSymbol.put(id, sym);
            nifty500Symbols.add(sym);
        });
    }

    public void ensureLoadedLazily() {
        if (loaded.compareAndSet(false, true)) {
            refreshAll();
        }
    }

    @Scheduled(cron = "0 45 7 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshAll() {
        if (!properties.isEnabled()) {
            return;
        }
        loadNifty500();
        loadDhanScripMaster();
    }

    public Optional<String> getSecurityId(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        ensureLoadedLazily();
        return Optional.ofNullable(symbolToSecId.get(symbol.trim().toUpperCase(Locale.ROOT)));
    }

    public Optional<String> getSymbolBySecurityId(String securityId) {
        if (securityId == null || securityId.isBlank()) return Optional.empty();
        return Optional.ofNullable(secIdToSymbol.get(securityId.trim()));
    }

    public boolean isInLiquidUniverse(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        ensureLoadedLazily();
        return nifty500Symbols.contains(symbol.trim().toUpperCase(Locale.ROOT));
    }

    public Set<String> getLiquidUniverseSymbols() {
        ensureLoadedLazily();
        return Collections.unmodifiableSet(nifty500Symbols);
    }

    public String getIndustry(String symbol) {
        if (symbol == null) return "General";
        ensureLoadedLazily();
        return symbolToIndustry.getOrDefault(symbol.trim().toUpperCase(Locale.ROOT), "General");
    }

    public String resolveSectorIndexName(String symbol) {
        String industry = getIndustry(symbol).toUpperCase(Locale.ROOT);
        if (industry.contains("FINANCIAL") || industry.contains("BANK")) return "BANKNIFTY";
        if (industry.contains("INFORMATION TECHNOLOGY") || industry.matches(".*\\bIT\\b.*")) return "NIFTYIT";
        if (industry.contains("AUTO")) return "NIFTY AUTO";
        if (industry.contains("HEALTHCARE") || industry.contains("PHARMA")) return "NIFTY PHARMA";
        if (industry.contains("METAL")) return "NIFTY METAL";
        if (industry.contains("FAST MOVING") || industry.contains("FMCG")) return "NIFTY FMCG";
        if (industry.contains("OIL") || industry.contains("POWER") || industry.contains("ENERGY")) return "NIFTY ENERGY";
        return "NIFTY";
    }

    public String resolveSectorIndexSecurityId(String symbol) {
        String idxName = resolveSectorIndexName(symbol);
        return indexToSecId.getOrDefault(idxName, NIFTY_50_SEC_ID);
    }

    private static final List<String> CORE_INDEX_NAMES = List.of(
            "NIFTY", "BANKNIFTY", "NIFTYIT", "NIFTY AUTO", "NIFTY PHARMA", "NIFTY METAL", "NIFTY FMCG", "NIFTY ENERGY"
    );

    public Collection<String> getAllTrackedIndexIds() {
        List<String> ids = new ArrayList<>();
        for (String name : CORE_INDEX_NAMES) {
            String id = indexToSecId.get(name);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    public String resolveSymbolFromCompanyName(String companyName) {
        if (companyName == null || companyName.isBlank()) return "";
        ensureLoadedLazily();
        String norm = normalizeCompany(companyName);
        return companyToSymbol.getOrDefault(norm, "");
    }

    public int getNifty500Count() {
        return nifty500Symbols.size();
    }

    public int getMappedSymbolCount() {
        return symbolToSecId.size();
    }

    void loadNifty500() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(NIFTY500_URL))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> res = httpClientSupplier.get()
                    .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .get(15, TimeUnit.SECONDS);

            if (res.statusCode() != 200 || res.body() == null) {
                log.warn("Nifty 500 CSV fetch returned HTTP {}", res.statusCode());
                return;
            }

            String[] lines = res.body().split("\\r?\\n");
            int count = 0;
            for (int i = 1; i < lines.length; i++) {
                List<String> cols = parseCsvLine(lines[i]);
                if (cols.size() >= 3) {
                    String company = cols.get(0).trim();
                    String industry = cols.get(1).trim();
                    String symbol = cols.get(2).trim().toUpperCase(Locale.ROOT);
                    if (!symbol.isBlank()) {
                        nifty500Symbols.add(symbol);
                        if (!industry.isBlank()) {
                            symbolToIndustry.put(symbol, industry);
                        }
                        if (!company.isBlank()) {
                            companyToSymbol.put(normalizeCompany(company), symbol);
                        }
                        count++;
                    }
                }
            }
            log.info("Loaded {} Nifty 500 liquid universe constituents.", count);
        } catch (Exception e) {
            log.warn("Failed to load Nifty 500 CSV: {}", e.getMessage());
        }
    }

    void loadDhanScripMaster() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(DHAN_SCRIP_MASTER_URL))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> res = httpClientSupplier.get()
                    .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .get(30, TimeUnit.SECONDS);

            if (res.statusCode() != 200 || res.body() == null) {
                log.warn("Dhan scrip master fetch returned HTTP {}", res.statusCode());
                return;
            }

            String[] lines = res.body().split("\\r?\\n");
            int eqCount = 0;
            int idxCount = 0;
            for (int i = 1; i < lines.length; i++) {
                List<String> cols = parseCsvLine(lines[i]);
                if (cols.size() < 15) continue;

                String exch = cols.get(0).trim();
                String seg = cols.get(1).trim();
                String secId = cols.get(2).trim();
                String instName = cols.get(3).trim();
                String tradingSym = cols.get(5).trim().toUpperCase(Locale.ROOT);
                String series = cols.get(14).trim();
                String fullName = cols.size() > 15 ? cols.get(15).trim() : "";

                if ("NSE".equals(exch) && "E".equals(seg) && "EQUITY".equals(instName) && "EQ".equals(series)) {
                    symbolToSecId.put(tradingSym, secId);
                    secIdToSymbol.put(secId, tradingSym);
                    if (!fullName.isBlank()) {
                        companyToSymbol.putIfAbsent(normalizeCompany(fullName), tradingSym);
                    }
                    eqCount++;
                } else if ("NSE".equals(exch) && "I".equals(seg) && "INDEX".equals(instName)) {
                    indexToSecId.put(tradingSym, secId);
                    idxCount++;
                }
            }
            log.info("Loaded {} Dhan NSE_EQ securities and {} IDX_I indices.", eqCount, idxCount);
        } catch (Exception e) {
            log.warn("Failed to load Dhan scrip master CSV: {}", e.getMessage());
        }
    }

    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    private static String normalizeCompany(String s) {
        return s.toUpperCase(Locale.ROOT)
                .replaceAll("\\b(LIMITED|LTD\\.?|PRIVATE|PVT\\.?)\\b", "")
                .replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}

