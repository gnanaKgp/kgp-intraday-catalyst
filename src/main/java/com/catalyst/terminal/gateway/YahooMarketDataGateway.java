package com.catalyst.terminal.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.catalyst.terminal.model.MarketSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class YahooMarketDataGateway implements MarketDataGateway {

    private static final Logger log = LoggerFactory.getLogger(YahooMarketDataGateway.class);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final Supplier<HttpClient> httpClientSupplier;
    private final ObjectMapper objectMapper;

    public YahooMarketDataGateway(Supplier<HttpClient> httpClientSupplier, ObjectMapper objectMapper) {
        this.httpClientSupplier = httpClientSupplier;
        this.objectMapper = objectMapper;
    }

    @Override
    public MarketSnapshot fetchSnapshot(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return MarketSnapshot.empty("UNKNOWN");
        }
        String clean = symbol.trim().toUpperCase();
        MarketSnapshot nse = fetchFromYahoo(clean, ".NS");
        if (nse.ltp() != null) {
            return nse;
        }
        return fetchFromYahoo(clean, ".BO");
    }

    private MarketSnapshot fetchFromYahoo(String symbol, String suffix) {
        Double ltp = null;
        Double dayChangePct = null;
        Double marketCapCr = null;

        try {
            String chartUrl = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + suffix;
            JsonNode chartRoot = getJson(chartUrl);
            if (chartRoot != null) {
                JsonNode meta = chartRoot.at("/chart/result/0/meta");
                double price = meta.at("/regularMarketPrice").asDouble(0.0);
                if (price > 0) {
                    ltp = round2(price);
                    double prevClose = meta.at("/chartPreviousClose").asDouble(price);
                    if (prevClose > 0) {
                        dayChangePct = round2(((price - prevClose) / prevClose) * 100.0);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Yahoo chart fetch failed for {}{}: {}", symbol, suffix, e.getMessage());
        }

        try {
            String summaryUrl = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/" + symbol + suffix + "?modules=price";
            JsonNode summaryRoot = getJson(summaryUrl);
            if (summaryRoot != null) {
                JsonNode priceNode = summaryRoot.at("/quoteSummary/result/0/price");
                double rawMcap = priceNode.at("/marketCap/raw").asDouble(0.0);
                if (rawMcap > 0) {
                    marketCapCr = round2(rawMcap / 10_000_000.0);
                }
                if (ltp == null) {
                    double summaryPrice = priceNode.at("/regularMarketPrice/raw").asDouble(0.0);
                    if (summaryPrice > 0) {
                        ltp = round2(summaryPrice);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Yahoo quoteSummary fetch failed for {}{}: {}", symbol, suffix, e.getMessage());
        }

        String source = (ltp != null || marketCapCr != null) ? ("YAHOO" + suffix) : "UNAVAILABLE";
        return new MarketSnapshot(symbol, ltp, dayChangePct, marketCapCr, source, Instant.now());
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();

        HttpResponse<String> res = httpClientSupplier.get()
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .get(6, TimeUnit.SECONDS);

        if (res.statusCode() == 200 && res.body() != null) {
            return objectMapper.readTree(res.body());
        }
        return null;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

