package com.yibi.backend.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSecretsCheckTest {

    @Test
    void namesMissingDefaultAndShortSecretsWithoutPrintingThem() {
        Map<String, String> env = new HashMap<>();
        env.put("JWT_SECRET", "short");
        env.put("DB_PASSWORD", "yibi_secret");
        env.put("REDIS_PASSWORD", "");
        assertThat(ProductionSecretsCheck.problems(env::get)).containsExactly(
                "DB_PASSWORD vẫn là giá trị mặc định của bản dev",
                "REDIS_PASSWORD chưa được đặt",
                "MINIO_ROOT_PASSWORD chưa được đặt",
                "JWT_SECRET phải dài ít nhất 32 ký tự");
    }

    @Test
    void acceptsRealSecrets() {
        Map<String, String> env = Map.of("JWT_SECRET", "x".repeat(48), "DB_PASSWORD", "p1", "REDIS_PASSWORD", "p2",
                "MINIO_ROOT_PASSWORD", "p3");
        assertThat(ProductionSecretsCheck.problems(env::get)).isEmpty();
    }

    @Test
    void refusesTheDevelopmentJwtSecret() {
        Map<String, String> env = new HashMap<>(ProductionSecretsCheck.SECRETS);
        assertThat(ProductionSecretsCheck.problems(env::get)).hasSize(4);
    }
}
