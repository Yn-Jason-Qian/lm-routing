package com.lm.routing.infrastructure.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Caches real road distance+duration pairs in Redis to avoid redundant API calls.
 *
 * Key format: dist:{lat1}:{lng1}:{lat2}:{lng2}
 * Value: JSON with distanceMeters and durationSeconds
 */
@Slf4j
@Service
public class DistanceCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final Duration ttl;

    private static final String KEY_PREFIX = "dist";

    public DistanceCacheService(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${routing.cache.redis-ttl-days:7}") int ttlDays) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofDays(ttlDays);
    }

    /**
     * Cached distance entry.
     */
    public record CachedDistance(long distanceMeters, long durationSeconds) {}

    /**
     * Look up a cached distance between two coordinates.
     */
    public Optional<CachedDistance> get(double lat1, double lng1, double lat2, double lng2) {
        try {
            String key = buildKey(lat1, lng1, lat2, lng2);
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return Optional.empty();
            }

            // Value could be a LinkedHashMap from Jackson deserialization
            if (value instanceof java.util.Map<?, ?> map) {
                long dist = ((Number) map.get("distanceMeters")).longValue();
                long dur = ((Number) map.get("durationSeconds")).longValue();
                return Optional.of(new CachedDistance(dist, dur));
            }

            return Optional.empty();
        } catch (Exception e) {
            log.debug("Redis read failed (cache miss): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Cache a distance between two coordinates. Order-insensitive.
     */
    public void put(double lat1, double lng1, double lat2, double lng2,
                    long distanceMeters, long durationSeconds) {
        try {
            String key = buildKey(lat1, lng1, lat2, lng2);
            CachedDistance entry = new CachedDistance(distanceMeters, durationSeconds);
            redisTemplate.opsForValue().set(key, entry, ttl);
        } catch (Exception e) {
            log.debug("Redis write failed (cache skip): {}", e.getMessage());
        }
    }

    /**
     * Order-insensitive key: coordinates rounded to 6 decimal places (~0.1m precision)
     * and sorted so (A,B) and (B,A) produce the same key.
     */
    private String buildKey(double lat1, double lng1, double lat2, double lng2) {
        long a1 = Math.round(lat1 * 1_000_000);
        long o1 = Math.round(lng1 * 1_000_000);
        long a2 = Math.round(lat2 * 1_000_000);
        long o2 = Math.round(lng2 * 1_000_000);

        // Sort so (A,B) and (B,A) share the same cache key
        if (a1 < a2 || (a1 == a2 && o1 <= o2)) {
            return String.format("%s:%d:%d:%d:%d", KEY_PREFIX, a1, o1, a2, o2);
        } else {
            return String.format("%s:%d:%d:%d:%d", KEY_PREFIX, a2, o2, a1, o1);
        }
    }
}
