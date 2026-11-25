package com.yibi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Asks Gemini for JSON that follows a schema, trying each configured model and, on quota or key errors, each key —
 * the same order the question generator uses.
 */
@Slf4j
@Component
public class GeminiJsonClient {

    /** Counts Gemini answers for the admin system page; optional so the class still builds alone. */
    private GeminiUsageTracker usage;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUsage(GeminiUsageTracker usage) {
        this.usage = usage;
    }

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final List<String> apiKeys;
    private final List<String> models;

    public GeminiJsonClient(ObjectMapper mapper,
                            @Value("${GEMINI_API_KEY:}") String primaryKey,
                            @Value("${GEMINI_API_KEY_FALLBACK_1:}") String fallbackKey1,
                            @Value("${GEMINI_API_KEY_FALLBACK_2:}") String fallbackKey2,
                            @Value("${app.gemini.model:gemini-3.8-flash}") String primaryModel,
                            @Value("${app.gemini.fallback-models:gemini-3.6-flash,gemini-3.5-flash-lite}") String fallbackModels) {
        this.mapper = mapper;
        this.apiKeys = distinctNonBlank(List.of(primaryKey, fallbackKey1, fallbackKey2));
        List<String> configured = new ArrayList<>();
        configured.add(primaryModel);
        configured.addAll(Arrays.asList(fallbackModels.split(",")));
        List<String> resolved = distinctNonBlank(configured);
        this.models = resolved.isEmpty() ? List.of("gemini-3.8-flash") : resolved;
    }

    public boolean configured() {
        return !apiKeys.isEmpty();
    }

    public int keyCount() {
        return apiKeys.size();
    }

    public List<String> models() {
        return List.copyOf(models);
    }

    /** The parsed JSON answer; throws an AppException when every model and key failed. */
    public JsonNode generate(String prompt, Map<String, Object> schema, double temperature) {
        if (apiKeys.isEmpty()) {
            throw new AppException("Chưa cấu hình khoá Gemini (GEMINI_API_KEY).", HttpStatus.SERVICE_UNAVAILABLE);
        }
        List<String> failures = new ArrayList<>();
        for (String model : models) {
            for (String apiKey : apiKeys) {
                try {
                    JsonNode response = call(model, apiKey, prompt, schema, temperature);
                    String text = response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
                    if (text.isBlank()) {
                        failures.add(model + ": empty");
                        continue;
                    }
                    return mapper.readTree(stripCodeFence(text));
                } catch (StatusException ex) {
                    failures.add(model + ": HTTP " + ex.status);
                    // Quota and auth errors are key-scoped; anything else is model-scoped.
                    if (ex.status != 429 && ex.status != 401 && ex.status != 403) break;
                } catch (IOException ex) {
                    log.warn("Gemini {} returned unreadable JSON: {}", model, ex.getMessage());
                    failures.add(model + ": unreadable");
                    break;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AppException("Yêu cầu Gemini bị ngắt.", HttpStatus.SERVICE_UNAVAILABLE);
                }
            }
        }
        log.warn("Gemini failed: {}", failures);
        throw new AppException("Gemini đang bận hoặc hết lượt dùng. Hãy thử lại sau.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private JsonNode call(String model, String apiKey, String prompt, Map<String, Object> schema, double temperature)
            throws IOException, InterruptedException {
        if (!model.matches("[A-Za-z0-9._-]+")) throw new StatusException(400);
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "responseFormat", Map.of("text", Map.of("mimeType", "APPLICATION_JSON", "schema", schema)),
                        "temperature", temperature));
        HttpRequest request = HttpRequest.newBuilder(URI.create(GEMINI_URL + model + ":generateContent"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (usage != null) usage.record("listening-translate", model, response.statusCode());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Gemini {} answered HTTP {}", model, response.statusCode());
            throw new StatusException(response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private static List<String> distinctNonBlank(Collection<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
    }

    private static String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        return trimmed.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
    }

    private static final class StatusException extends RuntimeException {
        private final int status;

        private StatusException(int status) {
            super("Gemini request failed");
            this.status = status;
        }
    }
}
