package com.catalyst.terminal.service;

import com.catalyst.terminal.gateway.MarketDataGateway;
import com.catalyst.terminal.model.MarketSnapshot;
import com.catalyst.terminal.model.funnel.DhanQuote;
import com.catalyst.terminal.service.dhan.DhanAuthService;
import com.catalyst.terminal.service.dhan.DhanInstrumentService;
import com.catalyst.terminal.service.dhan.DhanMarketGateway;
import com.catalyst.terminal.service.dhan.DhanWebSocketFeedService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MarketEnrichmentService {

    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(15);
    private static final Duration MCAP_FALLBACK_TTL = Duration.ofMinutes(15);

    private final MarketDataGateway marketDataGateway;
    private final DhanAuthService dhanAuthService;
    private final DhanInstrumentService dhanInstrumentService;
    private final DhanMarketGateway dhanMarketGateway;
    private final DhanWebSocketFeedService dhanWebSocketFeedService;
    private final Map<String, MarketSnapshot> snapshotCache = new ConcurrentHashMap<>();
    private final Map<String, CachedMcap> mcapCache = new ConcurrentHashMap<>();

    public MarketEnrichmentService(MarketDataGateway marketDataGateway) {
        this(marketDataGateway, null, null, null, null);
    }

    @Autowired
    public MarketEnrichmentService(
            MarketDataGateway marketDataGateway,
            DhanAuthService dhanAuthService,
            DhanInstrumentService dhanInstrumentService,
            DhanMarketGateway dhanMarketGateway,
            DhanWebSocketFeedService dhanWebSocketFeedService
    ) {
        this.marketDataGateway = marketDataGateway;
        this.dhanAuthService = dhanAuthService;
        this.dhanInstrumentService = dhanInstrumentService;
        this.dhanMarketGateway = dhanMarketGateway;
        this.dhanWebSocketFeedService = dhanWebSocketFeedService;
    }

    public MarketSnapshot enrich(String symbol, Double marketCapOverrideCr) {
        if (symbol == null || symbol.isBlank()) {
            return MarketSnapshot.empty("UNKNOWN");
        }
        String key = symbol.trim().toUpperCase();
        MarketSnapshot base = snapshotCache.get(key);

        if (base == null || Duration.between(base.fetchedAt(), Instant.now()).compareTo(SNAPSHOT_TTL) > 0) {
            base = tryDhanSnapshot(key).orElseGet(() -> marketDataGateway.fetchSnapshot(key));
            if (base != null && base.marketCapCr() != null && base.marketCapCr() > 0) {
                mcapCache.put(key, new CachedMcap(base.marketCapCr(), Instant.now()));
            } else if (base != null && base.marketCapCr() == null) {
                CachedMcap cachedMcap = mcapCache.get(key);
                if (cachedMcap != null && Duration.between(cachedMcap.fetchedAt(), Instant.now()).compareTo(MCAP_FALLBACK_TTL) <= 0) {
                    base = new MarketSnapshot(base.symbol(), base.ltp(), base.dayChangePct(), cachedMcap.mcapCr(), base.source(), base.fetchedAt());
                }
            }
            if (base != null) {
                snapshotCache.put(key, base);
            }
        }

        if (marketCapOverrideCr != null && marketCapOverrideCr > 0) {
            return new MarketSnapshot(
                    key,
                    base != null ? base.ltp() : null,
                    base != null ? base.dayChangePct() : null,
                    marketCapOverrideCr,
                    "MANUAL_OVERRIDE",
                    Instant.now()
            );
        }

        return base != null ? base : MarketSnapshot.empty(key);
    }

    private Optional<MarketSnapshot> tryDhanSnapshot(String symbol) {
        if (dhanAuthService == null || !dhanAuthService.isConfigured() || dhanInstrumentService == null) {
            return Optional.empty();
        }
        Optional<String> secIdOpt = dhanInstrumentService.getSecurityId(symbol);
        if (secIdOpt.isEmpty()) {
            return Optional.empty();
        }
        String secId = secIdOpt.get();
        if (dhanWebSocketFeedService != null) {
            Optional<DhanQuote> wsQuote = dhanWebSocketFeedService.getLiveQuote(secId);
            if (wsQuote.isPresent() && wsQuote.get().ltp() > 0) {
                DhanQuote q = wsQuote.get();
                return Optional.of(enrichWithDhanCompanyInfoAndFallback(symbol, secId, q.ltp(), q.dayChangePct(), "DHAN_WS", q.fetchedAt()));
            }
        }
        if (dhanMarketGateway != null) {
            Map<String, DhanQuote> quotes = dhanMarketGateway.fetchQuotes(List.of(secId), List.of());
            DhanQuote q = quotes.get(secId);
            if (q != null && q.ltp() > 0) {
                return Optional.of(enrichWithDhanCompanyInfoAndFallback(symbol, secId, q.ltp(), q.dayChangePct(), "DHAN_REST", q.fetchedAt()));
            }
        }
        return Optional.empty();
    }

    private MarketSnapshot enrichWithDhanCompanyInfoAndFallback(
            String symbol,
            String secId,
            double ltp,
            double dayChangePct,
            String source,
            Instant fetchedAt
    ) {
        Double mcapCr = dhanMarketGateway != null ? dhanMarketGateway.fetchMarketCapCr(secId) : null;
        double effectiveChg = dayChangePct;
        if (effectiveChg == 0.0 || mcapCr == null) {
            MarketSnapshot yahoo = marketDataGateway.fetchSnapshot(symbol);
            if (yahoo != null) {
                if (effectiveChg == 0.0 && yahoo.dayChangePct() != null) {
                    effectiveChg = yahoo.dayChangePct();
                }
                if (mcapCr == null && yahoo.marketCapCr() != null) {
                    mcapCr = yahoo.marketCapCr();
                }
            }
        }
        return new MarketSnapshot(symbol, ltp, effectiveChg, mcapCr, source, fetchedAt);
    }

    private record CachedMcap(double mcapCr, Instant fetchedAt) {}
}

