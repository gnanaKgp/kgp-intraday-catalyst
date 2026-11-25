package com.catalyst.terminal.service;

import com.catalyst.terminal.config.AppProperties;
import com.catalyst.terminal.gateway.InstrumentMasterGateway;
import com.catalyst.terminal.gateway.NseFeedGateway;
import com.catalyst.terminal.repository.CatalystFilingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NseFeedPollerTest {

    private CatalystEvaluatorService evaluatorService;
    private NseFeedPoller poller;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getNse().setMarketHoursOnly(false);

        NseFeedGateway feedGw = Mockito.mock(NseFeedGateway.class);
        InstrumentMasterGateway instGw = Mockito.mock(InstrumentMasterGateway.class);
        when(instGw.downloadInstrumentsCsv()).thenReturn(Optional.empty());

        var mapper = new InstrumentMappingService(instGw);
        var firewall = new CatalystFirewall();
        evaluatorService = Mockito.mock(CatalystEvaluatorService.class);
        var repo = Mockito.mock(CatalystFilingRepository.class);
        var metrics = new PipelineMetricsService(repo, props);
        Executor directExecutor = Runnable::run;

        poller = new NseFeedPoller(props, feedGw, firewall, mapper, evaluatorService, metrics, directExecutor);
    }

    @Test
    void cachesInitialPollAndProcessesSubsequentCatalystsOnly() {
        String initialXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <item>
                    <title>Bharat Dynamics Limited</title>
                    <link>https://nsearchives.nseindia.com/corporate/BDL_001_Order.pdf</link>
                    <description>Received work order worth INR 500 Crores</description>
                    <pubDate>Wed, 25 Nov 2026 10:00:00 GMT</pubDate>
                  </item>
                </channel></rss>
                """;

        assertEquals(0, poller.processXmlFeed(initialXml));
        verifyNoInteractions(evaluatorService);

        String secondXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <item>
                    <title>Bharat Dynamics Limited</title>
                    <link>https://nsearchives.nseindia.com/corporate/BDL_001_Order.pdf</link>
                    <description>Received work order worth INR 500 Crores</description>
                    <pubDate>Wed, 25 Nov 2026 10:00:00 GMT</pubDate>
                  </item>
                  <item>
                    <title>Tata Consultancy Services Limited</title>
                    <link>https://nsearchives.nseindia.com/corporate/TCS_002_Window.pdf</link>
                    <description>Intimation of Trading Window closure</description>
                    <pubDate>Wed, 25 Nov 2026 10:01:00 GMT</pubDate>
                  </item>
                  <item>
                    <title>Rail Vikas Nigam Limited</title>
                    <link>https://nsearchives.nseindia.com/corporate/RVNL_003_LoA.pdf</link>
                    <description>Receipt of Letter of Award for EPC contract worth INR 900 Crores</description>
                    <pubDate>Wed, 25 Nov 2026 10:02:00 GMT</pubDate>
                  </item>
                </channel></rss>
                """;

        int offloaded = poller.processXmlFeed(secondXml);
        assertEquals(1, offloaded);
        verify(evaluatorService, times(1)).processLiveFiling(
                eq("https://nsearchives.nseindia.com/corporate/RVNL_003_LoA.pdf"),
                eq("RVNL"),
                eq("Rail Vikas Nigam Limited"),
                contains("Letter of Award"),
                eq("https://nsearchives.nseindia.com/corporate/RVNL_003_LoA.pdf")
        );
    }
}

