package com.lm.routing.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides per-provider rate limiters for external API call protection.
 */
@Configuration
public class RateLimitConfig {

    @Value("${routing.rate-limit.google-per-second:10}")
    private double googlePerSec;

    @Value("${routing.rate-limit.mapbox-per-second:5}")
    private double mapboxPerSec;

    @Value("${routing.rate-limit.bing-per-second:5}")
    private double bingPerSec;

    @Value("${routing.rate-limit.amap-per-second:5}")
    private double amapPerSec;

    @Value("${routing.rate-limit.aws-per-second:5}")
    private double awsPerSec;

    @Bean
    public RateLimiter googleRateLimiter() { return new RateLimiter(googlePerSec); }

    @Bean
    public RateLimiter mapboxRateLimiter() { return new RateLimiter(mapboxPerSec); }

    @Bean
    public RateLimiter bingRateLimiter() { return new RateLimiter(bingPerSec); }

    @Bean
    public RateLimiter amapRateLimiter() { return new RateLimiter(amapPerSec); }

    @Bean
    public RateLimiter awsRateLimiter() { return new RateLimiter(awsPerSec); }
}
