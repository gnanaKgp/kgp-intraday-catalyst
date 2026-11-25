package com.catalyst.terminal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Provides a shared async executor and a lazily-initialized HttpClient supplier
 * so Spring context boot does not open network selector sockets during offline tests.
 */
@Configuration
public class HttpAndAsyncConfig {

    @Bean(name = "catalystExecutor")
    public Executor catalystExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(12);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("catalyst-worker-");
        executor.initialize();
        return executor;
    }

    @Bean
    public Supplier<HttpClient> lazyHttpClientSupplier() {
        AtomicReference<HttpClient> ref = new AtomicReference<>();
        return () -> {
            HttpClient existing = ref.get();
            if (existing != null) {
                return existing;
            }
            HttpClient created = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(6))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            if (ref.compareAndSet(null, created)) {
                return created;
            }
            return ref.get();
        };
    }
}

