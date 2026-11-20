package com.yibi.backend.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the GEMINI_* entries of the git-ignored .env file (next to the backend, then one folder up) so the AI
 * features work without exporting the keys by hand. Only GEMINI_* is taken: the other entries there (database,
 * JWT, ...) keep coming from application.yml or real environment variables. Real environment variables and
 * every other property source still win, because this source is added last.
 */
public class DotEnvGeminiKeys implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "dotenvGeminiKeys";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if ("false".equalsIgnoreCase(environment.getProperty("app.dotenv.enabled"))) return;
        Map<String, Object> keys = new LinkedHashMap<>();
        for (Path file : List.of(Path.of(".env"), Path.of("..", ".env"))) {
            if (!Files.isRegularFile(file)) continue;
            try {
                parse(Files.readAllLines(file, StandardCharsets.UTF_8)).forEach(keys::putIfAbsent);
            } catch (IOException ignored) {
                // An unreadable .env is the same as no .env.
            }
        }
        if (!keys.isEmpty()) environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, keys));
    }

    /** {@code KEY=value}, {@code KEY = "value"} or {@code export KEY='value'}; comments and other keys skipped. */
    static Map<String, String> parse(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("export ")) line = line.substring(7).strip();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || eq <= 0) continue;
            String key = line.substring(0, eq).strip();
            if (!key.startsWith("GEMINI_")) continue;
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                    && value.charAt(value.length() - 1) == value.charAt(0)) {
                value = value.substring(1, value.length() - 1);
            } else {
                int hash = value.indexOf(" #");
                if (hash >= 0) value = value.substring(0, hash).strip();
            }
            if (!value.isEmpty()) out.putIfAbsent(key, value);
        }
        return out;
    }
}
