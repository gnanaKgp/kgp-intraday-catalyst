package com.catalyst.terminal;

import com.catalyst.terminal.gateway.*;
import com.catalyst.terminal.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalystTerminalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private NseFeedGateway nseFeedGateway;

    @MockBean
    private InstrumentMasterGateway instrumentMasterGateway;

    @MockBean
    private MarketDataGateway marketDataGateway;

    @MockBean
    private LlmGateway llmGateway;

    @MockBean
    private TelegramGateway telegramGateway;

    @Test
    void fullManualAnalyzeWatchlistSearchAndCsvFlow() throws Exception {
        when(instrumentMasterGateway.downloadInstrumentsCsv()).thenReturn(Optional.empty());
        when(marketDataGateway.fetchSnapshot("RVNL")).thenReturn(
                new MarketSnapshot("RVNL", 450.0, 1.8, 20000.0, "MOCK", Instant.now())
        );
        when(llmGateway.evaluateFiling(eq("RVNL"), anyString(), anyString(), any())).thenReturn(
                new GeminiCatalystResult(
                        "RVNL", Direction.BULLISH, 91, FilingCategory.ORDER_WIN,
                        4000.0, 20.0, true,
                        "RVNL wins ₹4,000 Cr railway EPC order",
                        "Order equals 20% of market cap, driving institutional momentum.",
                        "Execution margin risk"
                )
        );

        // 1. Pin RVNL to watchlist
        mockMvc.perform(post("/api/watchlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"RVNL\",\"notes\":\"Core rail play\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("RVNL"));

        // 2. Analyze filing via REST API
        AnalyzeFilingRequest req = new AnalyzeFilingRequest(
                "RVNL",
                "Rail Vikas Nigam Limited",
                "LoA worth Rs 4000 Cr",
                "Rail Vikas Nigam Limited has received a firm LoA valued at INR 4,000 Crores.",
                null,
                null
        );

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("RVNL"))
                .andExpect(jsonPath("$.catalyst_score").value(91))
                .andExpect(jsonPath("$.tier").value("TIER_1"))
                .andExpect(jsonPath("$.order_to_mcap_pct").value(20.0))
                .andExpect(jsonPath("$.watchlistHit").value(true));

        // 3. Query filtered feed
        mockMvc.perform(get("/api/filings")
                        .param("tier", "TIER_1")
                        .param("watchlistOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].symbol").value("RVNL"));

        // 4. Export CSV
        mockMvc.perform(get("/api/filings/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(content().string(containsString("RVNL")));

        // 5. Check system status
        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPersisted", greaterThanOrEqualTo(1)));
    }
}

