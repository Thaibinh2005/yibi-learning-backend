package com.yibi.backend.security.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fixed-window counters in Redis. When Redis is unreachable the limiter lets requests through (and logs it): an outage
 * of the counter must not lock every learner out.
 */
@Slf4j
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** What a counter says after a hit: its new value and the seconds until it resets. */
    public record Window(long count, long resetSeconds) { }

    /** Counts one hit on {@code key}; the window starts with the first hit. */
    public Window hit(String key, Duration window) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) redis.expire(key, window);
            return new Window(count == null ? 0 : count, ttl(key, window));
        } catch (RuntimeException ex) {
            log.warn("Rate limiter unavailable ({}); letting the request through", ex.getMessage());
            return new Window(0, 0);
        }
    }

    /** The current value of {@code key} without counting a hit. */
    public Window peek(String key, Duration window) {
        try {
            String value = redis.opsForValue().get(key);
            return new Window(value == null ? 0 : Long.parseLong(value), ttl(key, window));
        } catch (RuntimeException ex) {
            log.warn("Rate limiter unavailable ({}); letting the request through", ex.getMessage());
            return new Window(0, 0);
        }
    }

    public void reset(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException ex) {
            log.warn("Rate limiter unavailable ({})", ex.getMessage());
        }
    }

    private long ttl(String key, Duration window) {
        Long seconds = redis.getExpire(key);
        if (seconds == null || seconds < 0) {
            // A key left without expiry (e.g. a crash between INCR and EXPIRE) would block forever: give it one.
            redis.expire(key, window);
            return window.toSeconds();
        }
        return seconds;
    }
}
