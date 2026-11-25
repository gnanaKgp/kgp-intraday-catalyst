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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class HttpTelegramGateway implements TelegramGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpTelegramGateway.class);

    private final Supplier<HttpClient> httpClientSupplier;
    private final ObjectMapper objectMapper;

    public HttpTelegramGateway(Supplier<HttpClient> httpClientSupplier, ObjectMapper objectMapper) {
        this.httpClientSupplier = httpClientSupplier;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean sendHtmlMessage(String botToken, String chatId, String htmlText) {
        try {
            String url = "https://api.telegram.org/bot" + botToken.trim() + "/sendMessage";
            Map<String, Object> payload = Map.of(
                    "chat_id", chatId.trim(),
                    "text", htmlText,
                    "parse_mode", "HTML",
                    "disable_web_page_preview", true
            );

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> res = httpClientSupplier.get()
                    .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .get(8, TimeUnit.SECONDS);

            if (res.statusCode() == 200) {
                return true;
            }
            log.warn("Telegram API returned HTTP {}: {}", res.statusCode(), res.body());
        } catch (Exception e) {
            log.warn("Telegram send failed: {}", e.getMessage());
        }
        return false;
    }

    @Override
    public List<TelegramUpdate> fetchUpdates(String botToken, long offset) {
        List<TelegramUpdate> updates = new ArrayList<>();
        try {
            String url = "https://api.telegram.org/bot" + botToken.trim()
                    + "/getUpdates?offset=" + offset + "&timeout=2";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> res = httpClientSupplier.get()
                    .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .get(6, TimeUnit.SECONDS);

            if (res.statusCode() != 200) return updates;
            JsonNode root = objectMapper.readTree(res.body());
            if (!root.path("ok").asBoolean()) return updates;

            for (JsonNode u : root.path("result")) {
                long updateId = u.path("update_id").asLong();
                JsonNode msg = u.path("message");
                if (!msg.isMissingNode() && msg.has("text")) {
                    String fromChatId = String.valueOf(msg.path("chat").path("id").asLong());
                    String text = msg.path("text").asText("").trim();
                    updates.add(new TelegramUpdate(updateId, fromChatId, text));
                } else {
                    updates.add(new TelegramUpdate(updateId, "", ""));
                }
            }
        } catch (Exception e) {
            log.debug("Telegram getUpdates skipped: {}", e.getMessage());
        }
        return updates;
    }
}

