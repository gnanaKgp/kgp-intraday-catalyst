package com.catalyst.terminal.service;

import com.catalyst.terminal.gateway.LlmGateway;
import com.catalyst.terminal.model.*;
import com.catalyst.terminal.repository.CatalystFilingRepository;
import com.catalyst.terminal.repository.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class CatalystEvaluatorService {

    private static final Logger log = LoggerFactory.getLogger(CatalystEvaluatorService.class);

    private final InstrumentMappingService instrumentMapper;
    private final MarketEnrichmentService marketEnrichment;
    private final PdfExtractorService pdfExtractor;
    private final LlmGateway llmGateway;
    private final CatalystFilingRepository filingRepository;
    private final WatchlistRepository watchlistRepository;
    private final PipelineMetricsService metricsService;
    private final SseBroadcastService sseBroadcast;
    private final TelegramAlertService telegramAlertService;
    private final Queue<PendingLiveFiling> retryQueue = new ConcurrentLinkedQueue<>();
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public CatalystEvaluatorService(
            InstrumentMappingService instrumentMapper,
            MarketEnrichmentService marketEnrichment,
            PdfExtractorService pdfExtractor,
            LlmGateway llmGateway,
            CatalystFilingRepository filingRepository,
            WatchlistRepository watchlistRepository,
            PipelineMetricsService metricsService,
            SseBroadcastService sseBroadcast,
            TelegramAlertService telegramAlertService
    ) {
        this.instrumentMapper = instrumentMapper;
        this.marketEnrichment = marketEnrichment;
        this.pdfExtractor = pdfExtractor;
        this.llmGateway = llmGateway;
        this.filingRepository = filingRepository;
        this.watchlistRepository = watchlistRepository;
        this.metricsService = metricsService;
        this.sseBroadcast = sseBroadcast;
        this.telegramAlertService = telegramAlertService;
    }

    public CatalystFilingRecord processLiveFiling(
            String filingUid,
            String rawSymbol,
            String companyName,
            String headline,
            String attachmentUrl
    ) {
        if (filingRepository.existsByFilingUid(filingUid)) {
            return null;
        }
        String text = pdfExtractor.extractFromUrl(attachmentUrl);
        if (text.isBlank()) {
            text = headline != null ? headline : "";
        }
        return evaluateAndPersist(filingUid, rawSymbol, companyName, headline, text, attachmentUrl, null, "LIVE_FEED", 0);
    }

    public CatalystFilingRecord processManualAnalysis(AnalyzeFilingRequest req, byte[] uploadedPdfBytes) {
        String extracted = uploadedPdfBytes != null && uploadedPdfBytes.length > 0
                ? pdfExtractor.extractFromBytes(uploadedPdfBytes)
                : "";
        if (extracted.isBlank() && req.attachmentUrl() != null && !req.attachmentUrl().isBlank()) {
            extracted = pdfExtractor.extractFromUrl(req.attachmentUrl());
        }
        String textToAnalyze = !extracted.isBlank()
                ? extracted
                : (req.filingText() != null && !req.filingText().isBlank() ? req.filingText() : req.headline());

        if (textToAnalyze == null || textToAnalyze.isBlank()) {
            throw new IllegalArgumentException("Provide filingText, headline, attachmentUrl, or a readable PDF file.");
        }

        String uid = "MANUAL_" + System.currentTimeMillis();
        return evaluateAndPersist(
                uid,
                req.symbol(),
                req.companyName(),
                req.headline() != null && !req.headline().isBlank() ? req.headline() : "Manual Filing Analysis",
                textToAnalyze,
                req.attachmentUrl(),
                req.marketCapCrOverride(),
                "MANUAL_SANDBOX",
                0
        );
    }

    @Scheduled(fixedDelay = 30000)
    public void processRetryQueue() {
        int batch = Math.min(3, retryQueue.size());
        for (int i = 0; i < batch; i++) {
            PendingLiveFiling p = retryQueue.poll();
            if (p == null) break;
            if (filingRepository.existsByFilingUid(p.filingUid())) continue;
            evaluateAndPersist(
                    p.filingUid(), p.symbol(), p.companyName(), p.headline(),
                    p.filingText(), p.attachmentUrl(), null, "LIVE_FEED_RETRY", p.attempt()
            );
        }
    }

    private CatalystFilingRecord evaluateAndPersist(
            String filingUid,
            String rawSymbol,
            String companyName,
            String headline,
            String filingText,
            String attachmentUrl,
            Double marketCapOverrideCr,
            String sourceMode,
            int retryAttempt
    ) {
        String symbol = instrumentMapper.resolveSymbol(rawSymbol, companyName);
        MarketSnapshot market = marketEnrichment.enrich(symbol, marketCapOverrideCr);

        metricsService.incrementAiEvaluated();
        metricsService.recordEvent("AI_EVAL", "INFO", symbol, "Scoring filing with institutional rubric...");

        GeminiCatalystResult rawResult = llmGateway.evaluateFiling(symbol, companyName, filingText, market);
        if (isUpstreamUnavailable(rawResult)) {
            if ("MANUAL_SANDBOX".equals(sourceMode)) {
                metricsService.recordEvent("AI_EVAL", "WARN", symbol,
                        "Gemini upstream unavailable (503); skipped saving fallback record.");
                throw new IllegalStateException("Gemini API is temporarily unavailable (503). Please retry in a few seconds.");
            }
            if (retryAttempt < 3 && retryQueue.size() < 50) {
                retryQueue.offer(new PendingLiveFiling(
                        filingUid, symbol, companyName, headline, filingText, attachmentUrl, retryAttempt + 1
                ));
                metricsService.recordEvent("AI_EVAL", "WARN", symbol,
                        "Gemini unavailable; queued for retry #" + (retryAttempt + 1));
            } else {
                metricsService.recordEvent("AI_EVAL", "WARN", symbol,
                        "Gemini unavailable after retries; skipped persisting bogus NOISE record.");
            }
            return null;
        }

        CatalystFilingRecord validated = applyDeterministicGuardrails(
                filingUid, symbol, companyName, headline, attachmentUrl, market, rawResult, sourceMode
        );

        CatalystFilingRecord saved = filingRepository.save(validated);
        sseBroadcast.broadcastFiling(saved);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(saved);
        }

        String level = saved.tier() == SignalTier.TIER_1 ? "SUCCESS"
                : (saved.tier() == SignalTier.TIER_2 ? "INFO" : "WARN");
        metricsService.recordEvent("SCORED", level, saved.symbol(),
                String.format("[%s | %d/100 | %s] %s",
                        saved.tier().name(), saved.catalystScore(), saved.direction().name(), saved.headlineSummary()));

        telegramAlertService.notifyIfEligible(saved);
        log.info("Scored {} ({}) -> {} | Score: {} | Category: {} | Actionable: {}",
                saved.symbol(), saved.companyName(), saved.direction(), saved.catalystScore(),
                saved.category(), saved.isActionableIntraday());

        return saved;
    }

    private static boolean isUpstreamUnavailable(GeminiCatalystResult raw) {
        return raw != null
                && "Evaluation unavailable".equals(raw.headlineSummary())
                && "Upstream API error".equals(raw.keyRisk());
    }

    private record PendingLiveFiling(
            String filingUid,
            String symbol,
            String companyName,
            String headline,
            String filingText,
            String attachmentUrl,
            int attempt
    ) {}

    CatalystFilingRecord applyDeterministicGuardrails(
            String filingUid,
            String symbol,
            String companyName,
            String headline,
            String attachmentUrl,
            MarketSnapshot market,
            GeminiCatalystResult raw,
            String sourceMode
    ) {
        int clampedScore = Math.max(0, Math.min(100, raw != null ? raw.catalystScore() : 0));
        Direction direction = (raw != null && raw.direction() != null) ? raw.direction() : Direction.NEUTRAL;
        FilingCategory category = (raw != null && raw.category() != null) ? raw.category() : FilingCategory.NOISE;

        Double orderValueCr = raw != null ? raw.orderValueCr() : null;
        Double marketCapCr = market != null ? market.marketCapCr() : null;
        Double orderToMcapPct = raw != null ? raw.orderToMcapPct() : null;

        if (orderValueCr != null && orderValueCr > 0 && marketCapCr != null && marketCapCr > 0) {
            orderToMcapPct = Math.round(((orderValueCr / marketCapCr) * 100.0) * 100.0) / 100.0;
        }

        boolean actionable = clampedScore >= 70 && direction != Direction.NEUTRAL;
        SignalTier tier = SignalTier.fromScore(clampedScore);
        boolean watchlistHit = watchlistRepository.exists(symbol);

        String summary = (raw != null && raw.headlineSummary() != null && !raw.headlineSummary().isBlank())
                ? raw.headlineSummary().trim()
                : (headline != null ? headline : "Corporate disclosure evaluated");
        String rationale = (raw != null && raw.institutionalRationale() != null && !raw.institutionalRationale().isBlank())
                ? raw.institutionalRationale().trim()
                : "No institutional catalyst rationale provided.";
        String risk = (raw != null && raw.keyRisk() != null && !raw.keyRisk().isBlank())
                ? raw.keyRisk().trim()
                : "Execution / market sentiment";

        return new CatalystFilingRecord(
                null,
                filingUid,
                symbol,
                companyName,
                headline,
                attachmentUrl,
                direction,
                clampedScore,
                tier,
                category,
                orderValueCr,
                orderToMcapPct,
                actionable,
                summary,
                rationale,
                risk,
                marketCapCr,
                market != null ? market.ltp() : null,
                market != null ? market.dayChangePct() : null,
                watchlistHit,
                sourceMode,
                LocalDateTime.now()
        );
    }
}

