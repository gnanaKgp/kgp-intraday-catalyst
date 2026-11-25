package com.catalyst.terminal.service.dhan;

import com.catalyst.terminal.config.TraderDhanProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Manages DhanHQ v2 JWT access tokens, validating JWT expiry and refreshing
 * automatically via DhanHQ's PIN + TOTP endpoint when needed.
 */
@Service
public class DhanAuthService {

    private static final Logger log = LoggerFactory.getLogger(DhanAuthService.class);
    private static final String AUTH_URL = "https://auth.dhan.co/app/generateAccessToken";
    private static final long EXPIRY_BUFFER_SECONDS = 300L;

    private final TraderDhanProperties properties;
    private final Supplier<HttpClient> httpClientSupplier;
    private final ObjectMapper objectMapper;

    private final AtomicReference<String> activeToken = new AtomicReference<>("");
    private final AtomicReference<String> lastAuthStatus = new AtomicReference<>("UNINITIALIZED");
    private volatile long cooldownUntilMs = 0L;
    private JdbcTemplate jdbcTemplate;

    public DhanAuthService(
            TraderDhanProperties properties,
            Supplier<HttpClient> httpClientSupplier,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.httpClientSupplier = httpClientSupplier;
        this.objectMapper = objectMapper;
        if (properties.getAccessToken() != null && !properties.getAccessToken().isBlank()) {
            activeToken.set(properties.getAccessToken().trim());
        }
    }

    @Autowired(required = false)
    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initTokenCache() {
        if (jdbcTemplate == null || !properties.isEnabled()) return;
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS dhan_token_cache (
                    client_id VARCHAR(64) PRIMARY KEY,
                    access_token VARCHAR(2048) NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
            """);
            if (!isTokenFresh(activeToken.get()) && !getClientId().isBlank()) {
                List<String> rows = jdbcTemplate.query(
                        "SELECT access_token FROM dhan_token_cache WHERE client_id = ?",
                        (rs, rn) -> rs.getString("access_token"),
                        getClientId()
                );
                if (!rows.isEmpty() && isTokenFresh(rows.get(0))) {
                    activeToken.set(rows.get(0).trim());
                    lastAuthStatus.set("TOKEN_VALID_CACHED");
                    log.info("Loaded fresh DhanHQ access token from H2 cache.");
                }
            }
        } catch (Exception e) {
            log.debug("Could not initialize Dhan token cache: {}", e.getMessage());
        }
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && properties.getClientId() != null && !properties.getClientId().isBlank()
                && ((properties.getPin() != null && !properties.getPin().isBlank()
                     && properties.getTotpSecret() != null && !properties.getTotpSecret().isBlank())
                    || !activeToken.get().isBlank());
    }

    public String getClientId() {
        return properties.getClientId() != null ? properties.getClientId().trim() : "";
    }

    public String getLastAuthStatus() {
        return lastAuthStatus.get();
    }

    public Optional<String> getValidAccessToken() {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        String current = activeToken.get();
        if (isTokenFresh(current)) {
            lastAuthStatus.set("TOKEN_VALID");
            return Optional.of(current);
        }
        return refreshAccessToken();
    }

    public synchronized Optional<String> invalidateAndRefresh() {
        activeToken.set("");
        return refreshAccessToken();
    }

    @Scheduled(cron = "0 50 7 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledMorningRefresh() {
        if (!properties.isEnabled()) return;
        refreshAccessToken();
    }

    public synchronized Optional<String> refreshAccessToken() {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        String current = activeToken.get();
        if (isTokenFresh(current)) {
            lastAuthStatus.set("TOKEN_VALID");
            return Optional.of(current);
        }

        if (properties.getClientId().isBlank() || properties.getPin().isBlank() || properties.getTotpSecret().isBlank()) {
            lastAuthStatus.set("MISSING_TOTP_CREDENTIALS");
            return Optional.empty();
        }
        if (System.currentTimeMillis() < cooldownUntilMs) {
            return Optional.empty();
        }

        try {
            String totp = TotpUtil.generateCode(properties.getTotpSecret());
            HttpResponse<String> res = callAuthEndpoint(totp);

            // If local clock is skewed across a 30s TOTP boundary, retry once using Dhan server's Date header
            if (res.body() != null && res.body().contains("Invalid TOTP")) {
                Optional<String> serverDate = res.headers().firstValue("date");
                if (serverDate.isPresent()) {
                    long serverEpoch = ZonedDateTime.parse(serverDate.get(), DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toEpochSecond();
                    String syncedTotp = TotpUtil.generateCodeAt(properties.getTotpSecret(), serverEpoch);
                    if (!syncedTotp.equals(totp)) {
                        log.info("Retrying DhanHQ TOTP auth using server-synced epoch...");
                        res = callAuthEndpoint(syncedTotp);
                    }
                }
            }

            if (res.statusCode() == 200 && res.body() != null) {
                JsonNode root = objectMapper.readTree(res.body());
                String newToken = root.path("accessToken").asText("");
                if (newToken.isBlank()) {
                    newToken = root.path("token").asText("");
                }
                if (!newToken.isBlank()) {
                    String clean = newToken.trim();
                    activeToken.set(clean);
                    lastAuthStatus.set("TOTP_REFRESH_OK");
                    persistTokenToCache(clean);
                    log.info("DhanHQ access token refreshed via TOTP for client {}", getClientId());
                    return Optional.of(clean);
                }
            }
            cooldownUntilMs = System.currentTimeMillis() + 30_000L;
            if (res.body() != null && res.body().contains("once every 2 minutes")) {
                cooldownUntilMs = System.currentTimeMillis() + 65_000L;
            }
            lastAuthStatus.set("AUTH_HTTP_" + res.statusCode());
            log.warn("DhanHQ TOTP token refresh failed (HTTP {}): {}", res.statusCode(), res.body());
        } catch (Exception e) {
            cooldownUntilMs = System.currentTimeMillis() + 15_000L;
            lastAuthStatus.set("AUTH_ERROR");
            log.warn("DhanHQ TOTP token refresh error: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private HttpResponse<String> callAuthEndpoint(String totp) throws Exception {
        String url = AUTH_URL
                + "?dhanClientId=" + enc(properties.getClientId().trim())
                + "&pin=" + enc(properties.getPin().trim())
                + "&totp=" + enc(totp);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return httpClientSupplier.get()
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .get(10, TimeUnit.SECONDS);
    }

    private void persistTokenToCache(String token) {
        if (jdbcTemplate == null || getClientId().isBlank()) return;
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE dhan_token_cache SET access_token = ?, updated_at = CURRENT_TIMESTAMP WHERE client_id = ?",
                    token, getClientId()
            );
            if (updated == 0) {
                jdbcTemplate.update(
                        "INSERT INTO dhan_token_cache (client_id, access_token, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                        getClientId(), token
                );
            }
        } catch (Exception e) {
            log.debug("Failed to persist Dhan token cache: {}", e.getMessage());
        }
    }

    boolean isTokenFresh(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            return false;
        }
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return false;
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(decoded);
            long exp = payload.path("exp").asLong(0L);
            return exp > Instant.now().getEpochSecond() + EXPIRY_BUFFER_SECONDS;
        } catch (Exception e) {
            return false;
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}

