package com.catalyst.terminal.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class HttpNseFeedGateway implements NseFeedGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpNseFeedGateway.class);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final Supplier<HttpClient> httpClientSupplier;

    public HttpNseFeedGateway(Supplier<HttpClient> httpClientSupplier) {
        this.httpClientSupplier = httpClientSupplier;
    }

    @Override
    public Optional<String> fetchAnnouncementsXml(String feedUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(feedUrl))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.nseindia.com/")
                    .header("Accept", "application/xml, text/xml, */*")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> res = httpClientSupplier.get()
                    .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .get(9, TimeUnit.SECONDS);

            if (res.statusCode() == 200) {
                return Optional.ofNullable(res.body());
            }
            log.warn("NSE RSS feed returned HTTP {}", res.statusCode());
        } catch (Exception e) {
            log.warn("NSE RSS feed poll failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public byte[] downloadAttachmentBytes(String attachmentUrl) {
        if (attachmentUrl == null || attachmentUrl.isBlank()) {
            return new byte[0];
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(attachmentUrl.trim()))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.nseindia.com/")
                    .header("Accept", "application/pdf,*/*")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<byte[]> res = httpClientSupplier.get()
                    .sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                    .get(15, TimeUnit.SECONDS);

            if (res.statusCode() == 200 && res.body() != null) {
                return res.body();
            }
            log.warn("PDF download failed (HTTP {}) for {}", res.statusCode(), attachmentUrl);
        } catch (Exception e) {
            log.warn("PDF download exception for {}: {}", attachmentUrl, e.getMessage());
        }
        return new byte[0];
    }
}

