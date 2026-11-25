package com.catalyst.terminal.service;

import com.catalyst.terminal.gateway.LlmGateway;
import com.catalyst.terminal.gateway.MarketDataGateway;
import com.catalyst.terminal.gateway.NseFeedGateway;
import com.catalyst.terminal.gateway.InstrumentMasterGateway;
import com.catalyst.terminal.model.*;
import com.catalyst.terminal.repository.CatalystFilingRepository;
import com.catalyst.terminal.repository.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class CatalystEvaluatorServiceTest {

    private CatalystFilingRepository filingRepository;
    private WatchlistRepository watchlistRepository;
    private LlmGateway llmGateway;
    private MarketDataGateway marketDataGateway;
    private CatalystEvaluatorService evaluatorService;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        filingRepository = new CatalystFilingRepository(jdbc);
        filingRepository.initSchema();
        watchlistRepository = new WatchlistRepository(jdbc);
        watchlistRepository.initSchema();
        jdbc.execute("DELETE FROM catalyst_filings");
        jdbc.execute("DELETE FROM watchlist_items");

        var props = new com.catalyst.terminal.config.AppProperties();
        InstrumentMasterGateway instGw = Mockito.mock(InstrumentMasterGateway.class);
        when(instGw.downloadInstrumentsCsv()).thenReturn(Optional.empty());
        var mapper = new InstrumentMappingService(instGw);

        marketDataGateway = Mockito.mock(MarketDataGateway.class);
        var marketEnrichment = new MarketEnrichmentService(marketDataGateway);

        NseFeedGateway feedGw = Mockito.mock(NseFeedGateway.class);
        var pdfExtractor = new PdfExtractorService(feedGw, props);

        llmGateway = Mockito.mock(LlmGateway.class);
        var metrics = new PipelineMetricsService(filingRepository, props);
        var sse = new SseBroadcastService();
        var tg = Mockito.mock(TelegramAlertService.class);

        evaluatorService = new CatalystEvaluatorService(
                mapper, marketEnrichment, pdfExtractor, llmGateway,
                filingRepository, watchlistRepository, metrics, sse, tg
        );
    }

    @Test
    void computesExactOrderToMcapPctAndActionableFlag() {
        watchlistRepository.upsert("BONDADA", "Solar EPC watch");
        when(marketDataGateway.fetchSnapshot("BONDADA")).thenReturn(
                new MarketSnapshot("BONDADA", 520.0, 2.1, 5000.0, "YAHOO.NS", Instant.now())
        );
        when(llmGateway.evaluateFiling(eq("BONDADA"), anyString(), anyString(), any())).thenReturn(
                new GeminiCatalystResult(
                        "BONDADA", Direction.BULLISH, 94, FilingCategory.ORDER_WIN,
                        1000.0, 12.0, false,
                        "Won ₹1,000 Cr solar EPC order (20% of MCap)",
                        "Large firm EPC contract represents 20% of current market capitalization.",
                        "Execution timeline risk"
                )
        );

        CatalystFilingRecord saved = evaluatorService.processLiveFiling(
                "uid-1", "BONDADA", "Bondada Engineering Limited", "Firm EPC order ₹1,000 Cr", ""
        );

        assertNotNull(saved.id());
        assertEquals(SignalTier.TIER_1, saved.tier());
        assertEquals(20.0, saved.orderToMcapPct(), 0.01);
        assertTrue(saved.isActionableIntraday());
        assertTrue(saved.watchlistHit());
        assertEquals(1L, filingRepository.countAll());
    }

    @Test
    void enforcesNeutralNotActionableAndClampsScore() {
        when(marketDataGateway.fetchSnapshot("TCS")).thenReturn(MarketSnapshot.empty("TCS"));
        when(llmGateway.evaluateFiling(eq("TCS"), anyString(), anyString(), any())).thenReturn(
                new GeminiCatalystResult(
                        "TCS", Direction.NEUTRAL, 120, FilingCategory.NOISE,
                        null, null, true,
                        "Routine update", "No impact.", "None"
                )
        );

        CatalystFilingRecord saved = evaluatorService.processLiveFiling(
                "uid-2", "TCS", "Tata Consultancy Services", "Update", ""
        );

        assertEquals(100, saved.catalystScore());
        assertFalse(saved.isActionableIntraday());
    }
}

