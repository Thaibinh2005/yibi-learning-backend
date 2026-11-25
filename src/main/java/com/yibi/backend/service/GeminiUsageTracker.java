package com.yibi.backend.service;

import com.yibi.backend.dto.admin.AdminDtos.GeminiUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counts Gemini answers per day, feature and model (ok, 429 quota, 503 overloaded, other), in a Redis hash kept a week.
 * Counting never breaks a call: when Redis is down the count is simply lost.
 */
@Slf4j
@Component
public class GeminiUsageTracker {

    private final StringRedisTemplate redis;

    public GeminiUsageTracker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(LocalDate day) {
        return "gemini:usage:" + day;
    }

    static String outcome(int status) {
        if (status >= 200 && status < 300) return "ok";
        if (status == 429 || status == 503) return String.valueOf(status);
        return "other";
    }

    public void record(String feature, String model, int status) {
        try {
            String key = key(LocalDate.now());
            redis.opsForHash().increment(key, feature + "|" + model + "|" + outcome(status), 1);
            redis.expire(key, Duration.ofDays(8));
        } catch (RuntimeException ex) {
            log.debug("Gemini usage not counted: {}", ex.getMessage());
        }
    }

    public List<GeminiUsage> today() {
        Map<String, Map<String, Long>> grouped = new TreeMap<>();
        try {
            for (Map.Entry<Object, Object> e : redis.opsForHash().entries(key(LocalDate.now())).entrySet()) {
                String[] parts = String.valueOf(e.getKey()).split("\\|", 3);
                if (parts.length < 3) continue;
                grouped.computeIfAbsent(parts[0] + "|" + parts[1], k -> new LinkedHashMap<>())
                        .merge(parts[2], Long.parseLong(String.valueOf(e.getValue())), Long::sum);
            }
        } catch (RuntimeException ex) {
            log.debug("Gemini usage not readable: {}", ex.getMessage());
        }
        List<GeminiUsage> out = new ArrayList<>();
        grouped.forEach((k, v) -> {
            String[] p = k.split("\\|", 2);
            out.add(new GeminiUsage(p[0], p[1], v));
        });
        return out;
    }
}
