package com.catalyst.terminal.service;

import com.catalyst.terminal.config.AppProperties;
import com.catalyst.terminal.gateway.TelegramGateway;
import com.catalyst.terminal.model.CatalystFilingRecord;
import com.catalyst.terminal.model.Direction;
import com.catalyst.terminal.model.SignalTier;
import com.catalyst.terminal.model.WatchlistItem;
import com.catalyst.terminal.repository.CatalystFilingRepository;
import com.catalyst.terminal.repository.WatchlistRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class TelegramAlertService {

    private static final Logger log = LoggerFactory.getLogger(TelegramAlertService.class);

    private final AppProperties properties;
    private final TelegramGateway telegramGateway;
    private final CatalystFilingRepository filingRepository;
    private final WatchlistRepository watchlistRepository;
    private final PipelineMetricsService metricsService;
    private final Executor executor;

    private volatile boolean pollingActive = false;
    private Thread pollingThread;
    private long lastUpdateId = 0;

    public TelegramAlertService(
            AppProperties properties,
            TelegramGateway telegramGateway,
            CatalystFilingRepository filingRepository,
            WatchlistRepository watchlistRepository,
            PipelineMetricsService metricsService,
            @Qualifier("catalystExecutor") Executor executor
    ) {
        this.properties = properties;
        this.telegramGateway = telegramGateway;
        this.filingRepository = filingRepository;
        this.watchlistRepository = watchlistRepository;
        this.metricsService = metricsService;
        this.executor = executor;
    }

    @PostConstruct
    public void init() {
        if (!isConfigured()) {
            log.info("Telegram alerts disabled or credentials absent.");
            return;
        }
        startCommandPoller();
        log.info("Telegram alert service & 2-way command poller active.");
    }

    @PreDestroy
    public void shutdown() {
        pollingActive = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
    }

    public boolean isConfigured() {
        AppProperties.Alerts a = properties.getAlerts();
        return a.isTelegramEnabled()
                && a.getTelegramBotToken() != null && !a.getTelegramBotToken().isBlank()
                && a.getTelegramChatId() != null && !a.getTelegramChatId().isBlank();
    }

    public boolean shouldAlert(CatalystFilingRecord r) {
        int minScore = properties.getAlerts().getMinScore();
        int watchMin = properties.getAlerts().getWatchlistMinScore();
        return r.catalystScore() >= minScore || (r.watchlistHit() && r.catalystScore() >= watchMin);
    }

    public CompletableFuture<Boolean> notifyIfEligible(CatalystFilingRecord r) {
        if (!isConfigured() || !shouldAlert(r)) {
            return CompletableFuture.completedFuture(false);
        }
        String html = formatAlertHtml(r);
        return CompletableFuture.supplyAsync(() -> {
            boolean sent = telegramGateway.sendHtmlMessage(
                    properties.getAlerts().getTelegramBotToken(),
                    properties.getAlerts().getTelegramChatId(),
                    html
            );
            if (sent) {
                metricsService.incrementAlertsSent();
            }
            return sent;
        }, executor);
    }

    public CompletableFuture<Boolean> sendTestAlert() {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(false);
        }
        String msg = """
                ✅ <b>NSE Catalyst Terminal — Alert Test</b>
                Your institutional catalyst alert pipeline is active.
                Commands: <code>/top</code> | <code>/tier1</code> | <code>/watchlist</code> | <code>/status</code>
                """;
        return CompletableFuture.supplyAsync(() -> telegramGateway.sendHtmlMessage(
                properties.getAlerts().getTelegramBotToken(),
                properties.getAlerts().getTelegramChatId(),
                msg
        ), executor);
    }

    public CompletableFuture<Boolean> sendRawHtmlAlert(String htmlMessage) {
        if (!isConfigured() || htmlMessage == null || htmlMessage.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.supplyAsync(() -> telegramGateway.sendHtmlMessage(
                properties.getAlerts().getTelegramBotToken(),
                properties.getAlerts().getTelegramChatId(),
                htmlMessage
        ), executor);
    }

    String formatAlertHtml(CatalystFilingRecord r) {
        String dirIcon = r.direction() == Direction.BULLISH ? "🟢"
                : (r.direction() == Direction.BEARISH ? "🔴" : "⚪");
        String tierBadge = r.tier() == SignalTier.TIER_1 ? "🔥 TIER 1" : r.tier().name();
        String watchTag = r.watchlistHit() ? " ⭐ <b>[WATCHLIST]</b>" : "";

        String orderLine = r.orderValueCr() != null
                ? String.format("\n• <b>Order Value:</b> ₹%.2f Cr%s",
                        r.orderValueCr(),
                        r.orderToMcapPct() != null ? String.format(" (<b>%.2f%% of MCap</b>)", r.orderToMcapPct()) : "")
                : "";

        String mktLine = (r.ltp() != null || r.marketCapCr() != null)
                ? String.format("\n• <b>Market:</b> LTP %s | MCap %s",
                        r.ltp() != null ? String.format("₹%.2f", r.ltp()) : "N/A",
                        r.marketCapCr() != null ? String.format("₹%.0f Cr", r.marketCapCr()) : "N/A")
                : "";

        String pdfLine = (r.attachmentUrl() != null && !r.attachmentUrl().isBlank())
                ? "\n\n📎 <a href=\"" + escHtml(r.attachmentUrl()) + "\">Open Exchange Filing</a>"
                : "";

        return String.format("""
                %s <b>%s</b> (%s) — <b>Score %d/100</b> [%s]%s
                ━━━━━━━━━━━━━━━━━━━━
                <b>%s</b>
                • <b>Category:</b> <code>%s</code> | <b>Direction:</b> <code>%s</code>%s%s

                🏦 <b>Institutional Rationale:</b>
                <i>%s</i>

                ⚠️ <b>Key Risk:</b> %s%s
                """,
                dirIcon, escHtml(r.symbol()), escHtml(r.companyName()), r.catalystScore(), tierBadge, watchTag,
                escHtml(r.headlineSummary()),
                r.category().name(), r.direction().name(), orderLine, mktLine,
                escHtml(r.institutionalRationale()),
                escHtml(r.keyRisk()),
                pdfLine
        );
    }

    private void startCommandPoller() {
        pollingActive = true;
        pollingThread = new Thread(() -> {
            while (pollingActive) {
                try {
                    List<TelegramGateway.TelegramUpdate> updates = telegramGateway.fetchUpdates(
                            properties.getAlerts().getTelegramBotToken(),
                            lastUpdateId + 1
                    );
                    for (TelegramGateway.TelegramUpdate u : updates) {
                        if (u.updateId() > lastUpdateId) {
                            lastUpdateId = u.updateId();
                        }
                        if (!u.text().isBlank()
                                && properties.getAlerts().getTelegramChatId().trim().equals(u.chatId())) {
                            handleCommand(u.text());
                        }
                    }
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {
                    try { Thread.sleep(4000); } catch (InterruptedException ie) { break; }
                }
            }
        }, "telegram-cmd-poller");
        pollingThread.setDaemon(true);
        pollingThread.start();
    }

    void handleCommand(String raw) {
        String cmd = raw.trim();
        String lower = cmd.toLowerCase();
        String token = properties.getAlerts().getTelegramBotToken();
        String chatId = properties.getAlerts().getTelegramChatId();

        if (lower.startsWith("/start") || lower.startsWith("/help")) {
            telegramGateway.sendHtmlMessage(token, chatId, """
                    🤖 <b>NSE Catalyst Terminal — Commands</b>
                    • <code>/status</code> — Poller & DB stats
                    • <code>/tier1</code> — Latest Tier 1 (90+) catalysts
                    • <code>/top</code> — Latest actionable (70+) catalysts
                    • <code>/watchlist</code> — List pinned symbols
                    • <code>/add SYMBOL</code> — Pin symbol to watchlist
                    • <code>/remove SYMBOL</code> — Unpin symbol
                    """);
        } else if (lower.startsWith("/status")) {
            var stats = metricsService.getStatsSnapshot();
            telegramGateway.sendHtmlMessage(token, chatId, String.format("""
                    ⚡ <b>Terminal Status</b>
                    • Polls: %s (Last: %s [%s])
                    • Filings Seen: %s | Dropped: %s | AI Scored: %s
                    • Tier 1 Today: %s | Tier 2 Today: %s
                    • Total Persisted: %s
                    """,
                    stats.get("totalPolls"), stats.get("lastPollTime"), stats.get("lastPollStatus"),
                    stats.get("totalFilingsSeen"), stats.get("firewallDropped"), stats.get("aiEvaluated"),
                    stats.get("tier1Today"), stats.get("tier2Today"), stats.get("totalPersisted")
            ));
        } else if (lower.startsWith("/tier1")) {
            sendTopList(90, "TIER 1 CATALYSTS (90+)");
        } else if (lower.startsWith("/top")) {
            sendTopList(70, "ACTIONABLE CATALYSTS (70+)");
        } else if (lower.startsWith("/watchlist")) {
            List<WatchlistItem> items = watchlistRepository.findAll();
            if (items.isEmpty()) {
                telegramGateway.sendHtmlMessage(token, chatId, "⭐ Watchlist is empty. Use <code>/add RVNL</code>.");
            } else {
                StringBuilder sb = new StringBuilder("⭐ <b>Pinned Watchlist</b>\n");
                items.forEach(i -> sb.append("• <code>").append(i.symbol()).append("</code>\n"));
                telegramGateway.sendHtmlMessage(token, chatId, sb.toString());
            }
        } else if (lower.startsWith("/add ")) {
            String sym = cmd.substring(5).trim().toUpperCase();
            if (!sym.isBlank()) {
                watchlistRepository.upsert(sym, "Added via Telegram");
                telegramGateway.sendHtmlMessage(token, chatId, "⭐ Added <code>" + escHtml(sym) + "</code> to watchlist.");
            }
        } else if (lower.startsWith("/remove ")) {
            String sym = cmd.substring(8).trim().toUpperCase();
            boolean removed = watchlistRepository.delete(sym);
            telegramGateway.sendHtmlMessage(token, chatId, removed
                    ? "🗑️ Removed <code>" + escHtml(sym) + "</code>."
                    : "Symbol not found in watchlist.");
        }
    }

    private void sendTopList(int minScore, String heading) {
        List<CatalystFilingRecord> items = filingRepository.search(null, null, null, minScore, null, null, null, null, 5);
        String token = properties.getAlerts().getTelegramBotToken();
        String chatId = properties.getAlerts().getTelegramChatId();
        if (items.isEmpty()) {
            telegramGateway.sendHtmlMessage(token, chatId, "No filings recorded with score >= " + minScore + ".");
            return;
        }
        StringBuilder sb = new StringBuilder("<b>").append(heading).append("</b>\n");
        for (CatalystFilingRecord r : items) {
            sb.append(String.format("• <b>%s</b> [%d/100 %s] — %s\n",
                    escHtml(r.symbol()), r.catalystScore(), r.direction().name(), escHtml(r.headlineSummary())));
        }
        telegramGateway.sendHtmlMessage(token, chatId, sb.toString());
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

