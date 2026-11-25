package com.catalyst.terminal.service;

import com.catalyst.terminal.config.AppProperties;
import com.catalyst.terminal.gateway.NseFeedGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NseFeedPoller {

    private static final Logger log = LoggerFactory.getLogger(NseFeedPoller.class);
    private static final int MAX_DEDUPE_CACHE = 5000;
    private static final Pattern SYMBOL_DESC_PATTERN = Pattern.compile("Symbol:\\s*([A-Za-z0-9&-]+)");

    private final AppProperties properties;
    private final NseFeedGateway feedGateway;
    private final CatalystFirewall firewall;
    private final InstrumentMappingService instrumentMapper;
    private final CatalystEvaluatorService evaluatorService;
    private final PipelineMetricsService metricsService;
    private final Executor executor;

    private final Map<String, Boolean> seenIds = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_DEDUPE_CACHE;
        }
    });

    private final AtomicBoolean firstPoll = new AtomicBoolean(true);

    public NseFeedPoller(
            AppProperties properties,
            NseFeedGateway feedGateway,
            CatalystFirewall firewall,
            InstrumentMappingService instrumentMapper,
            CatalystEvaluatorService evaluatorService,
            PipelineMetricsService metricsService,
            @Qualifier("catalystExecutor") Executor executor
    ) {
        this.properties = properties;
        this.feedGateway = feedGateway;
        this.firewall = firewall;
        this.instrumentMapper = instrumentMapper;
        this.evaluatorService = evaluatorService;
        this.metricsService = metricsService;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${catalyst.nse.poll-delay-ms:3000}")
    public void scheduledPoll() {
        if (!properties.getNse().isPollerEnabled()) {
            return;
        }
        Optional<String> xmlOpt = feedGateway.fetchAnnouncementsXml(properties.getNse().getFeedUrl());
        if (xmlOpt.isPresent()) {
            processXmlFeed(xmlOpt.get());
        } else {
            metricsService.recordPollFailure("FETCH_FAILED");
        }
    }

    public int processXmlFeed(String xml) {
        if (xml == null || xml.isBlank()) {
            return 0;
        }
        int offloaded = 0;
        int discovered = 0;

        try {
            if (xml.startsWith("\uFEFF")) {
                xml = xml.substring(1);
            }
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

            NodeList items = doc.getElementsByTagName("item");
            if (firstPoll.compareAndSet(true, false)) {
                for (int i = 0; i < items.getLength(); i++) {
                    Element item = (Element) items.item(i);
                    seenIds.put(buildUniqueId(item), Boolean.TRUE);
                }
                metricsService.recordPollSuccess(0);
                metricsService.recordEvent("SYSTEM", "INFO", "NSE",
                        "Cold-start cached " + seenIds.size() + " existing disclosures.");
                return 0;
            }

            boolean allowEvaluation = !properties.getNse().isMarketHoursOnly() || isWithinMarketHours();

            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String uid = buildUniqueId(item);
                if (seenIds.putIfAbsent(uid, Boolean.TRUE) != null) {
                    continue;
                }

                discovered++;
                metricsService.incrementFilingsSeen();

                String companyName = tag(item, "title");
                String link = tag(item, "link");
                String headline = tag(item, "description");
                String symbol = extractSymbol(link, headline, companyName);

                if (!allowEvaluation) {
                    metricsService.incrementFirewallDropped();
                    metricsService.recordEvent("FIREWALL", "FILTER", symbol, "Cached outside configured market hours.");
                    continue;
                }

                CatalystFirewall.FirewallDecision decision = firewall.evaluate(companyName, headline);
                if (decision.pass()) {
                    offloaded++;
                    metricsService.recordEvent("FIREWALL", "SUCCESS", symbol,
                            "Passed firewall (" + decision.reason() + ")");
                    CompletableFuture.runAsync(() -> {
                        try {
                            evaluatorService.processLiveFiling(uid, symbol, companyName, headline, link);
                        } catch (Exception e) {
                            log.error("Async evaluation failed for {} ({}): {}", companyName, symbol, e.getMessage());
                        }
                    }, executor);
                } else {
                    metricsService.incrementFirewallDropped();
                    metricsService.recordEvent("FIREWALL", "FILTER", symbol, "Dropped: " + decision.reason());
                }
            }

            metricsService.recordPollSuccess(discovered);
        } catch (Exception e) {
            log.error("Failed to parse NSE RSS XML: {}", e.getMessage());
            metricsService.recordPollFailure("PARSE_ERROR");
        }
        return offloaded;
    }

    public String extractSymbol(String link, String description, String companyName) {
        if (link != null && !link.isBlank()) {
            int lastSlash = link.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < link.length() - 1) {
                String file = link.substring(lastSlash + 1);
                int idx = file.indexOf('_');
                if (idx > 0) {
                    String prefix = file.substring(0, idx).trim().toUpperCase();
                    String stripped = prefix.replaceAll("\\d+$", "");
                    if (!stripped.isBlank() && instrumentMapper.isKnownRegularSymbol(stripped)) {
                        return stripped;
                    }
                    if (instrumentMapper.isKnownRegularSymbol(prefix)) {
                        return prefix;
                    }
                    if (!stripped.isBlank()) {
                        return stripped;
                    }
                }
            }
        }

        if (description != null && !description.isBlank()) {
            Matcher m = SYMBOL_DESC_PATTERN.matcher(description);
            if (m.find()) {
                return m.group(1).trim().toUpperCase();
            }
        }

        return instrumentMapper.resolveSymbol("", companyName);
    }

    public void resetForTesting() {
        firstPoll.set(true);
        seenIds.clear();
    }

    private boolean isWithinMarketHours() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        return !now.isBefore(LocalTime.of(9, 0)) && !now.isAfter(LocalTime.of(15, 35));
    }

    private String buildUniqueId(Element item) {
        String link = tag(item, "link");
        if (!link.isBlank()) {
            return link;
        }
        return tag(item, "title") + "_" + tag(item, "pubDate");
    }

    private static String tag(Element el, String name) {
        NodeList nl = el.getElementsByTagName(name);
        if (nl != null && nl.getLength() > 0 && nl.item(0).getTextContent() != null) {
            return nl.item(0).getTextContent().trim();
        }
        return "";
    }
}

