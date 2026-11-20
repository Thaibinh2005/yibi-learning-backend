package com.yibi.backend.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DotEnvGeminiKeysTest {

    @Test
    void readsOnlyGeminiKeysAndStripsQuotes() {
        Map<String, String> keys = DotEnvGeminiKeys.parse(List.of(
                "# comment",
                "GEMINI_API_KEY = \"abc\"",
                "GEMINI_API_KEY_FALLBACK_1='def'",
                "export GEMINI_MODEL=gemini-x # note",
                "GEMINI_API_KEY_FALLBACK_2 =",
                "JWT_SECRET = \"secret\"",
                "GEMINI_API_KEY = \"later\""));

        assertThat(keys).containsExactly(
                Map.entry("GEMINI_API_KEY", "abc"),
                Map.entry("GEMINI_API_KEY_FALLBACK_1", "def"),
                Map.entry("GEMINI_MODEL", "gemini-x"));
    }
}
