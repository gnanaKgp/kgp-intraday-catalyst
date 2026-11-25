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
public class KiteInstrumentGateway implements InstrumentMasterGateway {

    private static final Logger log = LoggerFactory.getLogger(KiteInstrumentGateway.class);
    private static final String KITE_INSTRUMENTS_URL = "https://api.kite.trade/instruments";

    private final Supplier<HttpClient> httpClientSupplier;

    public KiteInstrumentGateway(Supplier<HttpClient> httpClientSupplier) {
        this.httpClientSupplier = httpClientSupplier;
    }

    @Override
    public Optional<String> downloadInstrumentsCsv() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(KITE_INSTRUMENTS_URL))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> res = httpClientSupplier.get()
                    .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .get(10, TimeUnit.SECONDS);

            if (res.statusCode() == 200 && res.body() != null) {
                return Optional.of(res.body());
            }
            log.warn("Kite instruments endpoint returned HTTP {}", res.statusCode());
        } catch (Exception e) {
            log.warn("Could not download Kite instrument master: {}", e.getMessage());
        }
        return Optional.empty();
    }
}

