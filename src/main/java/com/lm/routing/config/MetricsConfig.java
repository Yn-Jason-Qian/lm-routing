package com.lm.routing.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers custom application metrics for Prometheus scraping.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public AppMetrics appMetrics(MeterRegistry registry) {
        return new AppMetrics(registry);
    }

    /**
     * Encapsulates all custom metrics for clean access from services.
     */
    public static class AppMetrics {

        private final MeterRegistry registry;

        /** Per-provider API call counters */
        private final Map<String, Counter> apiCallCounters = new ConcurrentHashMap<>();

        /** Per-provider API call timers */
        private final Map<String, Timer> apiCallTimers = new ConcurrentHashMap<>();

        public AppMetrics(MeterRegistry registry) {
            this.registry = registry;
        }

        /** Increment API call count for a provider. */
        public void recordApiCall(String provider) {
            apiCallCounters.computeIfAbsent(provider,
                    p -> Counter.builder("lmr_api_calls_total")
                            .tag("provider", p)
                            .description("External map API calls")
                            .register(registry))
                    .increment();
        }

        /** Record API call latency. */
        public void recordApiLatency(String provider, long millis) {
            apiCallTimers.computeIfAbsent(provider,
                    p -> Timer.builder("lmr_api_latency_seconds")
                            .tag("provider", p)
                            .description("External API call latency")
                            .register(registry))
                    .record(java.time.Duration.ofMillis(millis));
        }

        /** Record a route planning request. */
        public void recordRequest(String status, int pointCount) {
            Counter.builder("lmr_requests_total")
                    .tag("status", status)
                    .description("Total route planning requests")
                    .register(registry)
                    .increment();

            io.micrometer.core.instrument.DistributionSummary.builder("lmr_points_per_request")
                    .description("Stop count distribution")
                    .register(registry)
                    .record(pointCount);
        }
    }
}
