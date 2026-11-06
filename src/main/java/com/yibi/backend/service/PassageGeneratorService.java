package com.yibi.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes one short reading passage per group of vocabulary lessons with Gemini, then stores a READING
 * lesson and a LISTENING lesson for it. The audio for the listening lesson is added later by
 * {@link PassageAudioImportService}. Calls are spaced out for a free-tier key, and a re-run only fills
 * the passages that are still missing.
 */
@Slf4j
@Service
public class PassageGeneratorService {

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int LESSONS_PER_PASSAGE = 5;
    private static final int WORDS_IN_PROMPT = 40;
    private static final long PAUSE_BETWEEN_CALLS_MS = 8_000;
    private static final long QUOTA_WAIT_MS = 65_000;
    private static final int MAX_QUOTA_WAITS = 12;
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final List<String> apiKeys;
    private final List<String> models;

    public PassageGeneratorService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${GEMINI_API_KEY:}") String primaryKey,
            @Value("${GEMINI_API_KEY_FALLBACK_1:}") String fallbackKey1,
            @Value("${GEMINI_API_KEY_FALLBACK_2:}") String fallbackKey2,
            @Value("${app.gemini.model:gemini-3.8-flash}") String primaryModel,
            @Value("${app.gemini.fallback-models:gemini-3.6-flash,gemini-3.5-flash-lite}") String fallbackModels) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.apiKeys = Arrays.stream(new String[]{primaryKey, fallbackKey1, fallbackKey2}).map(PassageGeneratorService::clean).filter(k -> !k.isEmpty()).distinct().toList();
        List<String> all = new ArrayList<>(List.of(primaryModel));
        all.addAll(Arrays.asList(fallbackModels.split(",")));
        this.models = all.stream().map(String::trim).filter(m -> !m.isEmpty()).distinct().toList();
    }

    /** A .env value may arrive wrapped in quotes; the API rejects the key with them. */
    private static String clean(String value) {
        return value.replaceAll("^[\"'\\s]+|[\"'\\s]+$", "");
    }

    public record Result(int created, int skipped, boolean stoppedByQuota) {}

    /** Not transactional on purpose: each passage is saved as soon as it is written, so a quota stop keeps the earlier ones. */
    public Result generateAll(int limit) {
        if (apiKeys.isEmpty()) {
            throw new IllegalStateException("GEMINI_API_KEY is not set.");
        }
        log.info("Gemini keys loaded: {} (lengths {}), models {}", apiKeys.size(), apiKeys.stream().map(String::length).toList(), models);
        int created = 0;
        int skipped = 0;
        int failedInARow = 0;
        int quotaWaits = 0;
        for (Map<String, Object> course : jdbc.queryForList("SELECT id, hsk_level, title FROM courses ORDER BY hsk_level")) {
            long courseId = ((Number) course.get("id")).longValue();
            int level = ((Number) course.get("hsk_level")).intValue();
            String courseTitle = (String) course.get("title");
            List<Long> vocabLessons = jdbc.queryForList(
                    "SELECT id FROM lessons WHERE course_id = ? AND content_type = 'VOCABULARY' ORDER BY lesson_order", Long.class, courseId);
            int passages = (vocabLessons.size() + LESSONS_PER_PASSAGE - 1) / LESSONS_PER_PASSAGE;
            for (int n = 1; n <= passages; n++) {
                String key = "L" + level + "-P" + n;
                if (exists(courseId, key)) {
                    skipped++;
                    continue;
                }
                if (created >= limit) {
                    return new Result(created, skipped, false);
                }
                List<Long> group = vocabLessons.subList((n - 1) * LESSONS_PER_PASSAGE, Math.min(vocabLessons.size(), n * LESSONS_PER_PASSAGE));
                try {
                    JsonNode passage = writePassage(level, wordsOf(group));
                    save(courseId, courseTitle, level, n, key, passage);
                    created++;
                    failedInARow = 0;
                    quotaWaits = 0;
                    log.info("Passage {} saved ({} created so far).", key, created);
                    Thread.sleep(PAUSE_BETWEEN_CALLS_MS);
                } catch (QuotaException e) {
                    if (++quotaWaits > MAX_QUOTA_WAITS) {
                        log.warn("Gemini quota still reached at {} after {} waits; run again later to continue.", key, MAX_QUOTA_WAITS);
                        return new Result(created, skipped, true);
                    }
                    log.info("Gemini quota reached at {}; waiting a minute before trying again.", key);
                    try {
                        Thread.sleep(QUOTA_WAIT_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return new Result(created, skipped, true);
                    }
                    n--;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Result(created, skipped, false);
                } catch (RuntimeException | IOException e) {
                    log.warn("Passage {} skipped: {}", key, e.getMessage());
                    if (++failedInARow >= 3) {
                        log.warn("Three passages failed in a row; stopping.");
                        return new Result(created, skipped, false);
                    }
                }
            }
        }
        return new Result(created, skipped, false);
    }

    /** Stores the bundled passages (data/hsk_passages_2025.json) that are not in the database yet, so no Gemini key is needed. */
    public int importBundled() {
        JsonNode doc;
        try (var in = new org.springframework.core.io.ClassPathResource("data/hsk_passages_2025.json").getInputStream()) {
            doc = objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read data/hsk_passages_2025.json", e);
        }
        int imported = 0;
        for (JsonNode p : doc.path("passages")) {
            int level = p.path("level").asInt();
            List<Map<String, Object>> courses = jdbc.queryForList("SELECT id, title FROM courses WHERE hsk_level = ?", level);
            if (courses.isEmpty()) continue;
            long courseId = ((Number) courses.get(0).get("id")).longValue();
            String key = p.path("key").asText();
            if (exists(courseId, key)) continue;
            save(courseId, (String) courses.get(0).get("title"), level, p.path("n").asInt(), key, p);
            imported++;
        }
        return imported;
    }

    private boolean exists(long courseId, String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM lessons WHERE course_id = ? AND content_type = 'READING'
                               AND lesson_payload -> 'source' ->> 'passage_key' = ?)
                """, Boolean.class, courseId, key));
    }

    private List<String> wordsOf(List<Long> lessonIds) {
        List<String> words = new ArrayList<>();
        for (Long id : lessonIds) {
            words.addAll(jdbc.queryForList("""
                    SELECT v.simplified FROM lesson_content_items i
                    JOIN hsk_vocabulary_records v ON v.raw_record_id = i.raw_record_id
                    WHERE i.lesson_id = ? ORDER BY i.item_order
                    """, String.class, id));
        }
        // Spread the sample over the whole group instead of taking only the first lesson's words.
        List<String> sample = new ArrayList<>();
        int step = Math.max(1, words.size() / WORDS_IN_PROMPT);
        for (int i = 0; i < words.size() && sample.size() < WORDS_IN_PROMPT; i += step) sample.add(words.get(i));
        return sample;
    }

    private JsonNode writePassage(int level, List<String> words) throws IOException, InterruptedException {
        String prompt = buildPrompt(level, words);
        QuotaException quota = null;
        List<String> failures = new ArrayList<>();
        for (String model : models) {
            for (String key : apiKeys) {
                try {
                    JsonNode response = call(model, key, prompt);
                    String text = response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
                    JsonNode passage = objectMapper.readTree(text.replaceAll("^```(?:json)?\\s*|\\s*```$", ""));
                    validate(passage);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) passage).put("model", model);
                    return passage;
                } catch (HttpStatusException e) {
                    failures.add(model + " HTTP " + e.status + (e.detail.isEmpty() ? "" : " " + e.detail.substring(0, Math.min(160, e.detail.length()))));
                    if (e.status == 429) quota = new QuotaException();
                } catch (IOException | IllegalStateException e) {
                    failures.add(model + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }
        if (quota != null) throw quota;
        throw new IllegalStateException("Gemini returned no usable passage (" + String.join(", ", failures) + ").");
    }

    private void validate(JsonNode p) {
        if (!HAN.matcher(p.path("passage").asText("")).find()) throw new IllegalStateException("no Chinese passage");
        if (p.path("translation_vi").asText("").isBlank()) throw new IllegalStateException("no translation");
        JsonNode questions = p.path("questions");
        if (questions.size() < 2) throw new IllegalStateException("too few questions");
        for (JsonNode q : questions) {
            boolean answerListed = false;
            for (JsonNode o : q.path("options")) answerListed |= o.asText().equals(q.path("answer").asText());
            if (!answerListed) throw new IllegalStateException("answer is not one of the options");
        }
    }

    private void save(long courseId, String courseTitle, int level, int n, String key, JsonNode p) {
        int order = jdbc.queryForObject("SELECT COALESCE(MAX(lesson_order), 0) FROM lessons WHERE course_id = ?", Integer.class, courseId);
        String label = level == 7 ? "7–9" : String.valueOf(level);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("book_title", courseTitle);
        source.put("hsk_level", level);
        source.put("passage_key", key);
        source.put("origin", "ai_generated");
        source.put("model", p.path("model").asText());

        Map<String, Object> reading = new LinkedHashMap<>();
        reading.put("title", p.path("title").asText());
        reading.put("text", p.path("passage").asText());
        reading.put("pinyin", p.path("pinyin").asText());
        reading.put("translation_vi", p.path("translation_vi").asText());
        Object questions = objectMapper.convertValue(p.path("questions"), List.class);

        insertLesson(courseId, "Bài đọc HSK " + label + " – số " + n, order + 1, "READING", source,
                Map.of("readings", List.of(reading), "questions", questions));
        Map<String, Object> listening = new LinkedHashMap<>();
        listening.put("title", p.path("title").asText());
        listening.put("transcript", p.path("passage").asText());
        listening.put("pinyin", p.path("pinyin").asText());
        listening.put("translation_vi", p.path("translation_vi").asText());
        insertLesson(courseId, "Bài nghe HSK " + label + " – số " + n, order + 2, "LISTENING", source,
                Map.of("listenings", List.of(listening), "questions", questions));
        jdbc.update("UPDATE courses SET total_lessons = (SELECT COUNT(*) FROM lessons WHERE course_id = ?) WHERE id = ?", courseId, courseId);
    }

    private void insertLesson(long courseId, String title, int order, String type, Map<String, Object> source, Map<String, Object> sections) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", 1);
        payload.put("lesson_number", order);
        payload.put("title", title);
        payload.put("source", source);
        payload.put("sections", sections);
        try {
            jdbc.update("INSERT INTO lessons (course_id, title, lesson_order, content_type, lesson_payload) VALUES (?, ?, ?, ?, ?::jsonb)",
                    courseId, title, order, type, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String buildPrompt(int level, List<String> words) {
        String length = switch (level) {
            case 1 -> "4 đến 5 câu rất ngắn (khoảng 40 chữ Hán)";
            case 2 -> "5 đến 6 câu ngắn (khoảng 70 chữ Hán)";
            case 3 -> "khoảng 120 chữ Hán";
            case 4 -> "khoảng 160 chữ Hán";
            case 5 -> "khoảng 220 chữ Hán";
            case 6 -> "khoảng 280 chữ Hán";
            default -> "khoảng 350 chữ Hán";
        };
        return """
                Bạn là giáo viên tiếng Trung cho người Việt. Hãy viết một đoạn văn ngắn bằng chữ Hán giản thể cho trình độ HSK %s, dài %s, về một tình huống đời thường có đầu có cuối.
                Hãy dùng ít nhất 8 từ trong danh sách từ vựng bên dưới; ngoài danh sách chỉ dùng từ phổ biến ở cấp thấp hơn hoặc bằng HSK %s. Đoạn văn phải tự nhiên, không phải danh sách câu rời.
                Trả về JSON gồm: title (tên đoạn bằng tiếng Việt, ngắn), passage (đoạn văn chữ Hán), pinyin (pinyin đầy đủ có dấu thanh), translation_vi (bản dịch tiếng Việt),
                questions (3 câu trắc nghiệm hiểu nội dung, viết bằng tiếng Việt; mỗi câu có question, options gồm 3 hoặc 4 lựa chọn, answer trùng chính xác một option, explanation giải thích ngắn bằng tiếng Việt).
                Từ vựng: %s
                """.formatted(level == 7 ? "7–9" : String.valueOf(level), length, level == 7 ? "7–9" : String.valueOf(level), String.join("、", words));
    }

    private JsonNode call(String model, String apiKey, String prompt) throws IOException, InterruptedException {
        Map<String, Object> question = Map.of("type", "object",
                "properties", Map.of("question", Map.of("type", "string"),
                        "options", Map.of("type", "array", "items", Map.of("type", "string")),
                        "answer", Map.of("type", "string"), "explanation", Map.of("type", "string")),
                "required", List.of("question", "options", "answer", "explanation"));
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("title", Map.of("type", "string"), "passage", Map.of("type", "string"),
                        "pinyin", Map.of("type", "string"), "translation_vi", Map.of("type", "string"),
                        "questions", Map.of("type", "array", "items", question)),
                "required", List.of("title", "passage", "pinyin", "translation_vi", "questions"));
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "responseFormat", Map.of("text", Map.of("mimeType", "APPLICATION_JSON", "schema", schema)),
                        "temperature", 0.7));
        HttpRequest request = HttpRequest.newBuilder(URI.create(GEMINI_URL + model + ":generateContent"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new HttpStatusException(response.statusCode(), objectMapper.readTree(response.body()).path("error").path("message").asText(""));
        }
        return objectMapper.readTree(response.body());
    }

    private static class HttpStatusException extends RuntimeException {
        private final int status;
        private final String detail;
        private HttpStatusException(int status, String detail) { super("Gemini HTTP " + status); this.status = status; this.detail = detail; }
    }

    private static class QuotaException extends RuntimeException {}
}
