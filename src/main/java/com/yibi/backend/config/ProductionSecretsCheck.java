package com.yibi.backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On a real server (profile {@code prod}) the backend refuses to start while a secret is missing or still has the
 * development default written in application.yml: that file is public on GitHub, so a default JWT secret would let
 * anyone sign tokens, an admin's included. Local runs (no profile) keep the defaults.
 */
@Component
@Profile("prod")
public class ProductionSecretsCheck {

    /** Environment variable → the development default it must not equal. */
    static final Map<String, String> SECRETS = new LinkedHashMap<>();

    static {
        SECRETS.put("JWT_SECRET", "yibi-secure-jwt-secret-key-at-least-32-chars-2026-development");
        SECRETS.put("DB_PASSWORD", "yibi_secret");
        SECRETS.put("REDIS_PASSWORD", "yibi_redis_secret");
        SECRETS.put("MINIO_ROOT_PASSWORD", "yibi_minio_secret123");
    }

    private final Environment environment;

    public ProductionSecretsCheck(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void check() {
        List<String> problems = problems(environment::getProperty);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Profile prod: cấu hình bí mật chưa an toàn, backend không khởi động. "
                    + String.join("; ", problems));
        }
    }

    /** What is wrong, by variable name only (values are never printed). */
    static List<String> problems(java.util.function.Function<String, String> lookup) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> secret : SECRETS.entrySet()) {
            String value = lookup.apply(secret.getKey());
            if (value == null || value.isBlank()) out.add(secret.getKey() + " chưa được đặt");
            else if (value.equals(secret.getValue())) out.add(secret.getKey() + " vẫn là giá trị mặc định của bản dev");
        }
        String jwt = lookup.apply("JWT_SECRET");
        if (jwt != null && !jwt.isBlank() && jwt.length() < 32) out.add("JWT_SECRET phải dài ít nhất 32 ký tự");
        return out;
    }
}
