package com.catalyst.terminal.controller;

import com.catalyst.terminal.config.AppProperties;
import com.catalyst.terminal.model.PipelineEvent;
import com.catalyst.terminal.service.PipelineMetricsService;
import com.catalyst.terminal.service.SseBroadcastService;
import com.catalyst.terminal.service.TelegramAlertService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AppProperties properties;
    private final PipelineMetricsService metricsService;
    private final SseBroadcastService sseBroadcastService;
    private final TelegramAlertService telegramAlertService;

    @Value("${logging.file.name:logs/catalyst-terminal.log}")
    private String logFilePath;

    public SystemController(
            AppProperties properties,
            PipelineMetricsService metricsService,
            SseBroadcastService sseBroadcastService,
            TelegramAlertService telegramAlertService
    ) {
        this.properties = properties;
        this.metricsService = metricsService;
        this.sseBroadcastService = sseBroadcastService;
        this.telegramAlertService = telegramAlertService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>(metricsService.getStatsSnapshot());
        boolean geminiConfigured = properties.getGemini().getApiKey() != null
                && !properties.getGemini().getApiKey().isBlank();
        status.put("serverTimeIst", LocalTime.now().format(TIME_FMT));
        status.put("geminiConfigured", geminiConfigured);
        status.put("geminiModel", properties.getGemini().getModel());
        status.put("telegramConfigured", telegramAlertService.isConfigured());
        status.put("pollerEnabled", properties.getNse().isPollerEnabled());
        status.put("marketHoursOnly", properties.getNse().isMarketHoursOnly());
        status.put("sseSubscribers", sseBroadcastService.getActiveSubscribers());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/events")
    public ResponseEntity<List<PipelineEvent>> getEvents(@RequestParam(defaultValue = "80") int limit) {
        return ResponseEntity.ok(metricsService.getRecentEvents(Math.max(1, Math.min(limit, 250))));
    }

    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> getLogs(
            @RequestParam(defaultValue = "200") int lines,
            @RequestParam(defaultValue = "ALL") String level
    ) {
        int safeLines = Math.max(1, Math.min(lines, 1000));
        String file = (logFilePath == null || logFilePath.isBlank()) ? "logs/catalyst-terminal.log" : logFilePath.trim();
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            return ResponseEntity.ok(Map.of(
                    "file", file,
                    "exists", false,
                    "lines", List.of()
            ));
        }

        List<String> tail = readTailLines(path, safeLines * 3);
        String lvl = level != null ? level.trim().toUpperCase() : "ALL";
        List<String> filtered = tail.stream()
                .filter(l -> matchesLevel(l, lvl))
                .toList();
        if (filtered.size() > safeLines) {
            filtered = filtered.subList(filtered.size() - safeLines, filtered.size());
        }

        return ResponseEntity.ok(Map.of(
                "file", file,
                "exists", true,
                "lineCount", filtered.size(),
                "lines", filtered
        ));
    }

    private static boolean matchesLevel(String line, String level) {
        if ("ALL".equals(level) || level.isBlank()) return true;
        if ("WARN_ERROR".equals(level)) {
            return line.contains(" WARN ") || line.contains(" ERROR ");
        }
        return line.contains(" " + level + " ");
    }

    private static List<String> readTailLines(Path path, int maxLines) {
        List<String> result = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long fileLen = raf.length();
            if (fileLen == 0) return result;
            long maxBytes = Math.min(fileLen, 262_144L); // Last 256 KB
            long startPos = fileLen - maxBytes;
            raf.seek(startPos);
            byte[] buf = new byte[(int) maxBytes];
            raf.readFully(buf);
            String chunk = new String(buf, StandardCharsets.UTF_8);
            String[] split = chunk.split("\\r?\\n");
            int startIdx = (startPos > 0 && split.length > 1) ? 1 : 0; // skip partial first line
            for (int i = split.length - 1; i >= startIdx && result.size() < maxLines; i--) {
                result.add(split[i]);
            }
            Collections.reverse(result);
        } catch (IOException ignored) {}
        return result;
    }

    @PostMapping("/telegram/test")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> testTelegram() {
        if (!telegramAlertService.isConfigured()) {
            return CompletableFuture.completedFuture(ResponseEntity.ok(Map.of(
                    "sent", false,
                    "configured", false,
                    "message", "Configure TELEGRAM_ENABLED=true, TELEGRAM_BOT_TOKEN, and TELEGRAM_CHAT_ID in .env"
            )));
        }
        return telegramAlertService.sendTestAlert().thenApply(ok -> ResponseEntity.ok(Map.of(
                "sent", ok,
                "configured", true,
                "message", ok ? "Test alert sent to Telegram." : "Telegram API call failed."
        )));
    }
}

