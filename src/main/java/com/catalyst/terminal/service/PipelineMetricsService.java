package com.catalyst.terminal.service;

import com.catalyst.terminal.config.AppProperties;
import com.catalyst.terminal.model.PipelineEvent;
import com.catalyst.terminal.model.SignalTier;
import com.catalyst.terminal.repository.CatalystFilingRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PipelineMetricsService {

    private static final int MAX_EVENTS = 250;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final CatalystFilingRepository filingRepository;
    private final AppProperties properties;

    private final AtomicLong eventCounter = new AtomicLong(1);
    private final ConcurrentLinkedDeque<PipelineEvent> events = new ConcurrentLinkedDeque<>();

    private final AtomicLong totalPolls = new AtomicLong(0);
    private final AtomicLong totalFilingsSeen = new AtomicLong(0);
    private final AtomicLong firewallDropped = new AtomicLong(0);
    private final AtomicLong aiEvaluated = new AtomicLong(0);
    private final AtomicLong alertsSent = new AtomicLong(0);
    private final AtomicReference<String> lastPollTime = new AtomicReference<>("Never");
    private final AtomicReference<String> lastPollStatus = new AtomicReference<>("IDLE");

    public PipelineMetricsService(CatalystFilingRepository filingRepository, AppProperties properties) {
        this.filingRepository = filingRepository;
        this.properties = properties;
        recordEvent("SYSTEM", "INFO", "SYS", "Institutional Catalyst Terminal initialized.");
    }

    public void recordEvent(String stage, String level, String symbol, String message) {
        PipelineEvent ev = PipelineEvent.of(eventCounter.getAndIncrement(), stage, level, symbol, message);
        events.addFirst(ev);
        while (events.size() > MAX_EVENTS) {
            events.pollLast();
        }
    }

    public void recordPollSuccess(int newItems) {
        totalPolls.incrementAndGet();
        lastPollTime.set(LocalTime.now().format(TIME_FMT));
        lastPollStatus.set("OK");
        if (newItems > 0) {
            recordEvent("POLL", "INFO", "NSE", "Detected " + newItems + " new corporate filing(s)");
        }
    }

    public void recordPollFailure(String reason) {
        lastPollTime.set(LocalTime.now().format(TIME_FMT));
        lastPollStatus.set(reason);
    }

    public void incrementFilingsSeen() { totalFilingsSeen.incrementAndGet(); }
    public void incrementFirewallDropped() { firewallDropped.incrementAndGet(); }
    public void incrementAiEvaluated() { aiEvaluated.incrementAndGet(); }
    public void incrementAlertsSent() { alertsSent.incrementAndGet(); }

    public List<PipelineEvent> getRecentEvents(int limit) {
        List<PipelineEvent> list = new ArrayList<>();
        for (PipelineEvent ev : events) {
            list.add(ev);
            if (list.size() >= limit) break;
        }
        return Collections.unmodifiableList(list);
    }

    public Map<String, Object> getStatsSnapshot() {
        Map<SignalTier, Long> tierCounts = filingRepository.countByTierToday();
        return Map.ofEntries(
                Map.entry("totalPolls", totalPolls.get()),
                Map.entry("lastPollTime", lastPollTime.get()),
                Map.entry("lastPollStatus", lastPollStatus.get()),
                Map.entry("totalFilingsSeen", totalFilingsSeen.get()),
                Map.entry("firewallDropped", firewallDropped.get()),
                Map.entry("aiEvaluated", aiEvaluated.get()),
                Map.entry("alertsSent", alertsSent.get()),
                Map.entry("totalPersisted", filingRepository.countAll()),
                Map.entry("tier1Today", tierCounts.getOrDefault(SignalTier.TIER_1, 0L)),
                Map.entry("tier2Today", tierCounts.getOrDefault(SignalTier.TIER_2, 0L)),
                Map.entry("tier3Today", tierCounts.getOrDefault(SignalTier.TIER_3, 0L)),
                Map.entry("noiseToday", tierCounts.getOrDefault(SignalTier.NOISE, 0L))
        );
    }

    @Scheduled(cron = "0 0 4 * * ?", zone = "Asia/Kolkata")
    public void runRetentionPrune() {
        int days = Math.max(1, properties.getRetention().getDays());
        int purged = filingRepository.purgeOlderThanDays(days);
        if (purged > 0) {
            recordEvent("SYSTEM", "INFO", "DB", "Retention cleanup pruned " + purged + " records older than " + days + " days.");
        }
    }
}

