package com.catalyst.terminal.service.dhan;

import com.catalyst.terminal.model.funnel.Candle;
import com.catalyst.terminal.model.funnel.DhanQuote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * DhanHQ v2 REST Data Gateway for Market Quotes (/v2/marketfeed/quote),
 * Daily Historical Candles (/v2/charts/historical), and Intraday Candles (/v2/charts/intraday).
 */
@Service
public class DhanMarketGateway {

    private static final Logger log = LoggerFactory.getLogger(DhanMarketGateway.class);
    private static final String BASE_URL = "https://api.dhan.co/v2";

    private final DhanAuthService authService;
    private final Supplier<HttpClient> httpClientSupplier;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedCandles> dailyCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, CachedCandles> intradayCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, CachedMcap> companyMcapCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long lastRequestMs = 0L;

    public DhanMarketGateway(
            DhanAuthService authService,
            Supplier<HttpClient> httpClientSupplier,
            ObjectMapper objectMapper
    ) {
        this.authService = authService;
        this.httpClientSupplier = httpClientSupplier;
        this.objectMapper = objectMapper;
    }

    public Map<String, DhanQuote> fetchQuotes(List<String> nseSecurityIds, List<String> idxSecurityIds) {
        Map<String, DhanQuote> out = new HashMap<>();
        if (!authService.isConfigured()) {
            return out;
        }
        Map<String, List<Long>> body = new LinkedHashMap<>();
        List<Long> nseIds = parseNumericIds(nseSecurityIds);
        List<Long> idxIds = parseNumericIds(idxSecurityIds);
        if (!nseIds.isEmpty()) body.put("NSE_EQ", nseIds);
        if (!idxIds.isEmpty()) body.put("IDX_I", idxIds);
        if (body.isEmpty()) return out;

        JsonNode root = postJson("/marketfeed/quote", body);
        if (root == null) return out;

        JsonNode data = root.path("data");
        parseSegmentQuotes(data.path("NSE_EQ"), "NSE_EQ", out);
        parseSegmentQuotes(data.path("IDX_I"), "IDX_I", out);
        return out;
    }

    public List<Candle> fetchDailyCandles(String securityId, String exchangeSegment, String instrument, LocalDate from, LocalDate to) {
        if (!authService.isConfigured() || securityId == null || securityId.isBlank()) {
            return List.of();
        }
        String key = exchangeSegment + ":" + securityId.trim() + ":" + from + ":" + to;
        CachedCandles cached = dailyCache.get(key);
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).toMinutes() < 360) {
            return cached.candles();
        }
        Map<String, Object> body = Map.of(
                "securityId", securityId.trim(),
                "exchangeSegment", exchangeSegment,
                "instrument", instrument,
                "expiryCode", 0,
                "oi", false,
                "fromDate", from.toString(),
                "toDate", to.toString()
        );
        List<Candle> candles = parseColumnarCandles(postJson("/charts/historical", body));
        if (!candles.isEmpty()) {
            dailyCache.put(key, new CachedCandles(candles, Instant.now()));
        }
        return candles;
    }

    public List<Candle> fetchIntradayCandles(String securityId, String exchangeSegment, String instrument, String interval, LocalDate from, LocalDate to) {
        if (!authService.isConfigured() || securityId == null || securityId.isBlank()) {
            return List.of();
        }
        String key = exchangeSegment + ":" + securityId.trim() + ":" + interval + ":" + from + ":" + to;
        CachedCandles cached = intradayCache.get(key);
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).getSeconds() < 45) {
            return cached.candles();
        }
        Map<String, Object> body = Map.of(
                "securityId", securityId.trim(),
                "exchangeSegment", exchangeSegment,
                "instrument", instrument,
                "interval", interval,
                "oi", false,
                "fromDate", from.toString(),
                "toDate", to.toString()
        );
        List<Candle> candles = parseColumnarCandles(postJson("/charts/intraday", body));
        if (!candles.isEmpty()) {
            intradayCache.put(key, new CachedCandles(candles, Instant.now()));
        }
        return candles;
    }

    public Double fetchMarketCapCr(String securityId) {
        if (!authService.isConfigured() || securityId == null || securityId.isBlank()) {
            return null;
        }
        String cleanId = securityId.trim();
        CachedMcap cached = companyMcapCache.get(cleanId);
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).toMinutes() < 360) {
            return cached.mcapCr();
        }
        Map<String, Object> body = Map.of(
                "securityId", cleanId,
                "exchangeSegment", "NSE_EQ",
                "instrument", "EQUITY",
                "metrics", List.of("CO")
        );
        JsonNode root = postJson("/data/companyinfo", body);
        if (root != null) {
            String raw = root.at("/data/CO/MARKET_CAP").asText("").replace(",", "").trim();
            if (!raw.isBlank()) {
                try {
                    double mcap = Math.round(Double.parseDouble(raw) * 100.0) / 100.0;
                    if (mcap > 0) {
                        companyMcapCache.put(cleanId, new CachedMcap(mcap, Instant.now()));
                        return mcap;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private synchronized void throttle() {
        long now = System.currentTimeMillis();
        long wait = (lastRequestMs + 240L) - now;
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestMs = System.currentTimeMillis();
    }

    private JsonNode postJson(String path, Object payloadObj) {
        Optional<String> tokenOpt = authService.getValidAccessToken();
        if (tokenOpt.isEmpty()) {
            return null;
        }

        try {
            String jsonBody = objectMapper.writeValueAsString(payloadObj);
            for (int attempt = 1; attempt <= 2; attempt++) {
                throttle();
                String token = attempt == 1
                        ? tokenOpt.get()
                        : authService.getValidAccessToken().orElse("");
                if (token.isBlank()) break;

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + path))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("client-id", authService.getClientId())
                        .header("access-token", token)
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> res = httpClientSupplier.get()
                        .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                        .get(10, TimeUnit.SECONDS);

                if (res.statusCode() == 200 && res.body() != null) {
                    return objectMapper.readTree(res.body());
                }
                if (res.statusCode() == 401 && attempt == 1) {
                    log.warn("DhanHQ {} returned 401; refreshing token via TOTP...", path);
                    authService.invalidateAndRefresh();
                    continue;
                }
                if (res.statusCode() == 429 && attempt == 1) {
                    log.warn("DhanHQ {} hit 429 rate limit; backing off 700ms...", path);
                    Thread.sleep(700);
                    continue;
                }
                log.warn("DhanHQ {} returned HTTP {}: {}", path, res.statusCode(), res.body());
                break;
            }
        } catch (Exception e) {
            log.warn("DhanHQ {} call failed: {}", path, e.getMessage());
        }
        return null;
    }

    private record CachedCandles(List<Candle> candles, Instant fetchedAt) {}

    private void parseSegmentQuotes(JsonNode segNode, String segment, Map<String, DhanQuote> out) {
        if (segNode == null || !segNode.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> fields = segNode.fields();
        Instant now = Instant.now();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String secId = entry.getKey();
            JsonNode q = entry.getValue();
            double ltp = q.path("last_price").asDouble(0.0);
            double atp = q.path("average_price").asDouble(ltp);
            long volume = q.path("volume").asLong(0L);
            long buyQty = q.path("buy_quantity").asLong(0L);
            long sellQty = q.path("sell_quantity").asLong(0L);
            JsonNode ohlc = q.path("ohlc");
            double open = ohlc.path("open").asDouble(ltp);
            double high = ohlc.path("high").asDouble(ltp);
            double low = ohlc.path("low").asDouble(ltp);
            double prevClose = ohlc.path("close").asDouble(ltp);

            double bestBid = 0.0;
            double bestAsk = 0.0;
            JsonNode buyArr = q.path("depth").path("buy");
            JsonNode sellArr = q.path("depth").path("sell");
            if (buyArr.isArray() && !buyArr.isEmpty()) {
                bestBid = buyArr.get(0).path("price").asDouble(0.0);
            }
            if (sellArr.isArray() && !sellArr.isEmpty()) {
                bestAsk = sellArr.get(0).path("price").asDouble(0.0);
            }
            double spreadPct = (bestBid > 0 && bestAsk >= bestBid && ltp > 0)
                    ? ((bestAsk - bestBid) / ltp) * 100.0
                    : 0.0;

            out.put(secId, new DhanQuote(
                    secId, segment, ltp, atp, volume, open, high, low, prevClose,
                    buyQty, sellQty, bestBid, bestAsk, spreadPct, now
            ));
        }
    }

    static List<Candle> parseColumnarCandles(JsonNode root) {
        if (root == null || root.isMissingNode()) return List.of();
        JsonNode openArr = root.path("open");
        JsonNode highArr = root.path("high");
        JsonNode lowArr = root.path("low");
        JsonNode closeArr = root.path("close");
        JsonNode volArr = root.path("volume");
        JsonNode tsArr = root.path("timestamp");

        if (!openArr.isArray() || !tsArr.isArray()) return List.of();
        int n = Math.min(tsArr.size(), Math.min(openArr.size(), closeArr.size()));
        List<Candle> candles = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            candles.add(new Candle(
                    tsArr.get(i).asLong(0L),
                    openArr.get(i).asDouble(0.0),
                    highArr.path(i).asDouble(0.0),
                    lowArr.path(i).asDouble(0.0),
                    closeArr.get(i).asDouble(0.0),
                    volArr.path(i).asLong(0L)
            ));
        }
        return candles;
    }

    private static List<Long> parseNumericIds(List<String> ids) {
        if (ids == null) return List.of();
        List<Long> res = new ArrayList<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                try {
                    res.add(Long.parseLong(id.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return res;
    }

    private record CachedMcap(double mcapCr, Instant fetchedAt) {}
}

