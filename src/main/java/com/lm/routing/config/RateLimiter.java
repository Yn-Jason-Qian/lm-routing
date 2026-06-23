package com.lm.routing.config;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight token bucket rate limiter.
 *
 * Enforces a minimum interval between consecutive requests
 * to protect external APIs from accidental overspending.
 */
public class RateLimiter {

    private final long minIntervalNanos;
    private final AtomicLong lastRequestNanos;

    public RateLimiter(double permitsPerSecond) {
        this.minIntervalNanos = (long) (1_000_000_000L / permitsPerSecond);
        this.lastRequestNanos = new AtomicLong(0);
    }

    /**
     * Block until a permit is available.
     *
     * @return wait time in milliseconds (0 if no wait needed)
     */
    public long acquire() {
        long now = System.nanoTime();
        long last = lastRequestNanos.get();
        long nextAvailable = last + minIntervalNanos;

        if (now >= nextAvailable) {
            if (lastRequestNanos.compareAndSet(last, now)) {
                return 0;
            }
            // CAS failed, retry
            return acquire();
        }

        long waitNanos = nextAvailable - now;
        try {
            Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lastRequestNanos.set(System.nanoTime());
        return waitNanos / 1_000_000;
    }

    /**
     * Try to acquire a permit without blocking.
     *
     * @return true if acquired, false if rate-limited
     */
    public boolean tryAcquire() {
        long now = System.nanoTime();
        long last = lastRequestNanos.get();
        if (now >= last + minIntervalNanos) {
            return lastRequestNanos.compareAndSet(last, now);
        }
        return false;
    }
}
