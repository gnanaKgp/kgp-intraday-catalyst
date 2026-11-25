package com.catalyst.terminal.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Fetches NSE Pre-Open Market equilibrium prices and volumes (09:07–09:14 IST).
 */
@Component
public class NsePreOpenGateway {

    private static final Logger log = LoggerFactory.getLogger(NsePreOpenGateway.class);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";

    private final Supplier<HttpClient> httpClientSupplier;
    private final ObjectMapper objectMapper;

    public NsePreOpenGateway(Supplier<HttpClient> httpClientSupplier, ObjectMapper objectMapper) {
        this.httpClientSupplier = httpClientSupplier;
        this.objectMapper = objectMapper;
    }

    public List<PreOpenCandidate> fetchPreOpenCandidates() {
        List<PreOpenCandidate> out = new ArrayList<>();
        try {
            HttpClient client = httpClientSupplier.get();
            // Prime NSE session cookie
            HttpRequest homeReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.nseindia.com/"))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> homeRes = client.sendAsync(homeReq, HttpResponse.BodyHandlers.ofString())
                    .get(8, TimeUnit.SECONDS);

            List<String> cookies = homeRes.headers().allValues("set-cookie");
            String cookieHeader = String.join("; ", cookies.stream()
                    .map(c -> c.split(";", 2)[0])
                    .toList());

            HttpRequest apiReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.nseindia.com/api/market-data-pre-open?key=FO"))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.nseindia.com/market-data/pre-open-market-cm-and-emerge-market")
                    .header("Accept", "application/json")
                    .header("Cookie", cookieHeader)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> apiRes = client.sendAsync(apiReq, HttpResponse.BodyHandlers.ofString())
                    .get(10, TimeUnit.SECONDS);

            if (apiRes.statusCode() != 200 || apiRes.body() == null) {
                log.debug("NSE pre-open endpoint returned HTTP {}", apiRes.statusCode());
                return out;
            }

            JsonNode dataArr = objectMapper.readTree(apiRes.body()).path("data");
            if (!dataArr.isArray()) return out;

            for (JsonNode item : dataArr) {
                JsonNode meta = item.path("metadata");
                String symbol = meta.path("symbol").asText("").trim().toUpperCase();
                double eqPrice = meta.path("lastPrice").asDouble(0.0);
                double pChange = meta.path("pChange").asDouble(0.0);
                long volume = meta.path("totalTradedVolume").asLong(0L);
                double turnover = meta.path("totalTurnover").asDouble(0.0);
                if (!symbol.isBlank() && eqPrice > 0) {
                    out.add(new PreOpenCandidate(symbol, eqPrice, pChange, volume, turnover));
                }
            }
        } catch (Exception e) {
            log.debug("NSE pre-open fetch skipped: {}", e.getMessage());
        }
        return out;
    }

    public record PreOpenCandidate(String symbol, double equilibriumPrice, double gapPct, long preOpenVolume, double turnover) {}
}

