package com.yibi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.entity.Lesson;
import com.yibi.backend.exception.AppException;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.repository.LessonRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Creates and checkpoints one AI practice unit from one already-imported HSK lesson. */
@Slf4j
@Service
public class AiLessonContentService {

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String GENERATOR_VERSION = "hsk-mcq-v6-duolingo";
    private static final Set<String> ALLOWED_SKILLS = Set.of("vocabulary", "phonetics", "characters", "grammar");
    private static final Map<String, Set<String>> SKILL_SECTIONS = Map.of(
            "vocabulary", Set.of("vocabulary", "dialogues", "readings", "exercises"),
            "phonetics", Set.of("vocabulary", "phonetics", "exercises"),
            "characters", Set.of("vocabulary", "characters", "exercises"),
            "grammar", Set.of("grammar", "dialogues", "readings", "exercises")
    );

    private final LessonRepository lessonRepository;
    private final QuestionBankService questionBankService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final List<String> apiKeys;
    private final List<String> models;

    public AiLessonContentService(
            LessonRepository lessonRepository,
            QuestionBankService questionBankService,
            ObjectMapper objectMapper,
            @Value("${GEMINI_API_KEY:}") String primaryKey,
            @Value("${GEMINI_API_KEY_FALLBACK_1:}") String fallbackKey1,
            @Value("${GEMINI_API_KEY_FALLBACK_2:}") String fallbackKey2,
            @Value("${app.gemini.model:gemini-3.8-flash}") String primaryModel,
            @Value("${app.gemini.fallback-models:gemini-3.6-flash,gemini-3.5-flash-lite}") String fallbackModels
    ) {
        this.lessonRepository = lessonRepository;
        this.questionBankService = questionBankService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.apiKeys = distinctNonBlank(List.of(primaryKey, fallbackKey1, fallbackKey2));
        List<String> configuredModels = new ArrayList<>();
        configuredModels.add(primaryModel);
        configuredModels.addAll(Arrays.asList(fallbackModels.split(",")));
        List<String> resolvedModels = distinctNonBlank(configuredModels);
        this.models = resolvedModels.isEmpty() ? List.of("gemini-3.8-flash") : resolvedModels;
    }

    @Transactional
    public Map<String, Object> generateForLesson(Long lessonId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", lessonId));
        if (lesson.getCourse() == null || lesson.getCourse().getHskLevel() == null) {
            throw new BadRequestException("Bài học chưa gắn với cấp độ HSK.");
        }
        if (lesson.getLessonPayload() == null || lesson.getLessonPayload().isEmpty()) {
            throw new BadRequestException("Bài học chưa có lesson_payload. Hãy import JSON giáo trình trước.");
        }
        Map<String, Object> payload = new LinkedHashMap<>(lesson.getLessonPayload());
        Map<String, Object> sourceContext = buildSourceContext(payload);
        String sourceHash = sha256(GENERATOR_VERSION + "\n" + writeCanonicalJson(sourceContext));
        Object existingValue = payload.get("generated_lesson");
        if (existingValue instanceof Map<?, ?> existing
                && sourceHash.equals(String.valueOf(existing.get("source_hash")))) {
            Map<String, Object> cached = stringKeyMap(existing);
            cached.put("activities", assignStableIds(lesson.getId(), cached.get("activities")));
            questionBankService.storeGeneratedActivities(lesson.getId(), sourceHash, cached);
            payload.put("generated_lesson", cached);
            lesson.setLessonPayload(payload);
            lessonRepository.save(lesson);
            return cached;
        }
        if (apiKeys.isEmpty()) {
            throw new AppException("Chưa cấu hình GEMINI_API_KEY ở backend.", HttpStatus.SERVICE_UNAVAILABLE);
        }

        Map<String, Object> generated = generateWithGemini(lesson, sourceContext);
        generated.put("schema_version", 1);
        generated.put("generator_version", GENERATOR_VERSION);
        generated.put("origin", "ai_generated");
        generated.put("generated_at", Instant.now().toString());
        generated.put("source_hash", sourceHash);
        generated.put("source", payload.getOrDefault("source", Map.of()));
        questionBankService.storeGeneratedActivities(lesson.getId(), sourceHash, generated);
        payload.put("generated_lesson", generated);
        lesson.setLessonPayload(payload);
        lessonRepository.save(lesson);
        return generated;
    }

    private Map<String, Object> generateWithGemini(Lesson lesson, Map<String, Object> sourceContext) {
        String prompt = buildPrompt(lesson, sourceContext);
        List<String> failureSummaries = new ArrayList<>();
        for (String model : models) {
            for (String apiKey : apiKeys) {
                try {
                    JsonNode response = callGemini(model, apiKey, prompt);
                    String text = response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
                    if (text.isBlank()) throw new GeminiCallException(502, "empty response");
                    JsonNode generatedNode = objectMapper.readTree(stripCodeFence(text));
                    Map<String, Object> generated = objectMapper.convertValue(generatedNode, Map.class);
                    try {
                        validateGenerated(generated, sourceContext);
                    } catch (GeminiCallException ex) {
                        log.warn("Rejected AI quiz for lesson {}: {}", lesson.getId(), ex.getMessage());
                        throw new AppException("Gemini trả về bài tập chưa đủ căn cứ từ dữ liệu nguồn; chưa lưu kết quả. Hãy thử tạo lại.", HttpStatus.BAD_GATEWAY);
                    }
                    generated.put("activities", assignStableIds(lesson.getId(), generated.get("activities")));
                    generated.put("model", model);
                    return generated;
                } catch (GeminiCallException ex) {
                    failureSummaries.add(model + ": HTTP " + ex.statusCode);
                    // Quota/auth are key-scoped; try the next key. Other errors may be model-scoped.
                    if (ex.statusCode != 429 && ex.statusCode != 401 && ex.statusCode != 403
                            && ex.statusCode != 404 && ex.statusCode != 503 && ex.statusCode != 502) {
                        break;
                    }
                } catch (IOException ex) {
                    failureSummaries.add(model + ": invalid JSON");
                    break;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AppException("Yêu cầu Gemini bị gián đoạn. Hãy thử lại sau.", HttpStatus.SERVICE_UNAVAILABLE);
                }
            }
        }
        throw new AppException("Gemini chưa tạo được bài luyện tập cho bài này (" + String.join(", ", failureSummaries) + "). Hãy kiểm tra quota/API key rồi thử lại.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private JsonNode callGemini(String model, String apiKey, String prompt) throws IOException, InterruptedException {
        if (!model.matches("[A-Za-z0-9._-]+")) throw new GeminiCallException(400, "invalid model name");
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "responseFormat", Map.of("text", Map.of("mimeType", "APPLICATION_JSON", "schema", responseSchema())),
                        "temperature", 0.3
                )
        );
        HttpRequest request = HttpRequest.newBuilder(URI.create(GEMINI_URL + model + ":generateContent"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GeminiCallException(response.statusCode(), "Gemini request failed");
        }
        return objectMapper.readTree(response.body());
    }

    private Map<String, Object> responseSchema() {
        Map<String, Object> sourceRef = Map.of(
                "type", "object",
                "properties", Map.of("section", Map.of("type", "string"), "item_index", Map.of("type", "integer")),
                "required", List.of("section", "item_index")
        );
        Map<String, Object> activity = Map.of(
                "type", "object",
                "properties", Map.of(
                        "skill", Map.of("type", "string", "enum", List.of("vocabulary", "phonetics", "characters", "grammar")),
                        "topic_key", Map.of("type", "string"),
                        "difficulty", Map.of("type", "string", "enum", List.of("easy", "medium")),
                        "question", Map.of("type", "string"),
                        "options", Map.of("type", "array", "items", Map.of("type", "string")),
                        "answer", Map.of("type", "string"),
                        "evidence", Map.of("type", "string"),
                        "explanation", Map.of("type", "string"),
                        "source_refs", Map.of("type", "array", "items", sourceRef)
                ),
                "required", List.of("skill", "topic_key", "difficulty", "question", "options", "answer", "evidence", "explanation", "source_refs")
        );
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "introduction", Map.of("type", "string"),
                        "objectives", Map.of("type", "array", "items", Map.of("type", "string")),
                        "teaching_points", Map.of("type", "array", "items", Map.of("type", "string")),
                        "activities", Map.of("type", "array", "items", activity)
                ),
                "required", List.of("introduction", "objectives", "teaching_points", "activities")
        );
    }

    private String buildPrompt(Lesson lesson, Map<String, Object> sourceContext) {
        int hskLevel = lesson.getCourse().getHskLevel();
        return """
                Bạn là giáo viên thiết kế bài học tiếng Trung kiểu Duolingo cho người Việt ở trình độ HSK %d. JSON bên dưới là dữ liệu nền từ giáo trình; hãy biến dữ liệu đó thành một buổi học ngắn, có hướng dẫn dễ hiểu, ngữ cảnh và bài luyện tập tương tác.
                Tất cả nội dung hướng dẫn, câu hỏi và giải thích viết bằng tiếng Việt; từ/câu tiếng Trung phải giữ đúng chữ Hán và pinyin trong nguồn.

                QUY TẮC BẮT BUỘC:
                - Không chỉ chép lại JSON: giải thích lại kiến thức bằng lời đơn giản, kết hợp từ vựng và mẫu câu thành ví dụ/hội thoại ngắn để người học hiểu cách dùng.
                - Có thể tự tạo câu ví dụ và tình huống luyện tập mới bằng cách phối hợp từ, pinyin, chữ Hán và mẫu ngữ pháp trong bài. Không khẳng định câu ví dụ mới là câu trích nguyên văn từ sách.
                - Dùng cả dữ liệu vocabulary, phonetics, characters, grammar, dialogues, readings và exercises nếu phù hợp; ưu tiên nội dung trong bài này và tránh đưa từ mới chưa được dạy.
                - Tạo 2 đến 4 teaching_points ngắn gọn để dạy trước khi vào câu hỏi.
                - Tạo 3 đến 6 câu trắc nghiệm có thể chấm tự động. Mỗi câu có 3 hoặc 4 lựa chọn phân biệt và đúng duy nhất một đáp án.
                - Giáo trình không có sẵn đáp án: hãy tự suy luận đáp án đúng từ kiến thức trong item nguồn, rồi đặt answer trùng chính xác một option. Không yêu cầu answer phải xuất hiện nguyên văn trong nguồn.
                - Mỗi câu phải có evidence là một giá trị hoặc cụm nguyên văn được chép từ nguồn làm căn cứ kiến thức; answer có thể là kết quả áp dụng kiến thức đó.
                - Mỗi câu phải có topic_key ngắn, ổn định, mô tả đúng kiến thức đang kiểm tra (ví dụ grammar_bu_vs_mei); không gom mọi câu vào chủ đề chung nếu nguồn xác định được điểm cụ thể.
                - Mỗi câu phải có ít nhất một source_ref trỏ tới item chứa evidence; item_index đếm từ 0 (phần tử đầu tiên là 0). Backend sẽ đối chiếu evidence và tự chuẩn hóa source_refs.
                - Chỉ dùng skill: vocabulary, phonetics, characters, grammar. Không tạo câu ngữ pháp nếu nguồn không có grammar.
                - Đa dạng dạng hỏi: chọn nghĩa/pinyin/chữ Hán, điền từ vào câu mới, chọn câu đáp phù hợp hội thoại, hoặc hiểu ý một đoạn ngắn.
                - Explanation giải thích vì sao đáp án đúng và liên hệ với evidence. Với bài tập trong sách không có đáp án, tự giải bằng kiến thức đã trích; không coi đáp án trống là lỗi.
                - Không tạo hoạt động cần hình ảnh/âm thanh. Không tạo câu hỏi mà đáp án phụ thuộc vào ảnh hoặc thông tin bị thiếu.
                - Giữ độ khó HSK %d, chia bước ngắn và không lặp cùng một kiểu câu hỏi quá nhiều.

                Bài học: %s
                Dữ liệu nguồn JSON:
                %s
                """.formatted(hskLevel, hskLevel, lesson.getTitle(), writeJson(sourceContext));
    }

    private Map<String, Object> buildSourceContext(Map<String, Object> payload) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("schema_version", payload.get("schema_version"));
        context.put("lesson_number", payload.get("lesson_number"));
        context.put("title", payload.get("title"));
        context.put("source", payload.get("source"));
        Map<String, Object> sourceSections = asMap(payload.get("sections"));
        Map<String, Object> selectedSections = new LinkedHashMap<>();
        for (String key : List.of("objectives", "phonetics", "characters", "vocabulary", "dialogues", "grammar", "readings", "exercises", "culture_notes")) {
            Object value = sourceSections.get(key);
            selectedSections.put(key, value instanceof List<?> ? value : List.of());
        }
        context.put("sections", selectedSections);
        return context;
    }

    private void validateGenerated(Map<String, Object> generated, Map<String, Object> sourceContext) {
        List<?> objectives = asList(generated.get("objectives"));
        List<?> teachingPoints = asList(generated.get("teaching_points"));
        List<?> activities = asList(generated.get("activities"));
        if (objectives.isEmpty() || teachingPoints.size() < 2 || teachingPoints.size() > 4
                || activities.size() < 3 || activities.size() > 6) {
            throw new GeminiCallException(502, "invalid activity count");
        }
        Map<String, Object> sections = asMap(sourceContext.get("sections"));
        for (Object rawActivity : activities) {
            Map<String, Object> activity = asMap(rawActivity);
            String skill = String.valueOf(activity.getOrDefault("skill", ""));
            String question = String.valueOf(activity.getOrDefault("question", "")).trim();
            String topicKey = String.valueOf(activity.getOrDefault("topic_key", "")).trim();
            String answer = String.valueOf(activity.getOrDefault("answer", "")).trim();
            String evidence = String.valueOf(activity.getOrDefault("evidence", "")).trim();
            List<String> options = asList(activity.get("options")).stream().map(String::valueOf).map(String::trim).toList();
            if (!ALLOWED_SKILLS.contains(skill) || question.isBlank() || topicKey.isBlank() || answer.isBlank()
                    || evidence.isBlank()
                    || options.size() < 3 || options.size() > 4
                    || options.stream().map(AiLessonContentService::normalise).distinct().count() != options.size()
                    || options.stream().noneMatch(option -> normalise(option).equals(normalise(answer)))) {
                throw new GeminiCallException(502, "invalid generated activity");
            }
            // Textbooks generally omit answer keys. Verify the verbatim lesson evidence
            // used to infer the answer, then derive canonical citations instead of requiring
            // the inferred answer itself to occur in the source text.
            List<Map<String, Object>> verifiedRefs = findEvidenceRefs(skill, evidence, sections);
            if (verifiedRefs.isEmpty()) {
                throw new GeminiCallException(502, "evidence is not present in any allowed source item for skill " + skill);
            }
            activity.put("source_refs", verifiedRefs);
        }
    }

    private List<Map<String, Object>> findEvidenceRefs(
            String skill, String evidence, Map<String, Object> sections) {
        List<Map<String, Object>> verifiedRefs = new ArrayList<>();
        for (String section : SKILL_SECTIONS.get(skill)) {
            List<?> items = asList(sections.get(section));
            for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                Object item = items.get(itemIndex);
                if (containsSourceEvidence(item, evidence)) {
                    verifiedRefs.add(Map.of("section", section, "item_index", itemIndex));
                }
            }
        }
        return verifiedRefs;
    }

    private List<Map<String, Object>> assignStableIds(Long lessonId, Object value) {
        List<Map<String, Object>> activities = new ArrayList<>();
        int index = 1;
        for (Object raw : asList(value)) {
            Map<String, Object> activity = new LinkedHashMap<>(asMap(raw));
            activity.put("id", "hsk1-lesson-" + lessonId + "-ai-" + index++);
            activity.put("type", "multiple_choice");
            activity.put("origin", "ai_generated");
            activities.add(activity);
        }
        return activities;
    }

    private boolean containsSourceEvidence(Object sourceItem, String evidence) {
        String expected = normalise(evidence);
        for (String value : scalarValues(sourceItem)) {
            if (!expected.isBlank() && normalise(value).contains(expected)) return true;
        }
        return false;
    }

    private List<String> scalarValues(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(child -> values.addAll(scalarValues(child)));
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(child -> values.addAll(scalarValues(child)));
        } else if (value != null && !(value instanceof Number) && !(value instanceof Boolean)) {
            values.add(String.valueOf(value));
        }
        return values;
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static List<String> distinctNonBlank(Collection<String> values) {
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        return trimmed.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (IOException ex) { throw new AppException("Không thể xử lý JSON bài học.", HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    private String writeCanonicalJson(Object value) {
        return writeJson(canonicalize(value));
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::canonicalize).toList();
        }
        return value;
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<?> asList(Object value) { return value instanceof List<?> list ? list : List.of(); }

    private static Map<String, Object> stringKeyMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static class GeminiCallException extends RuntimeException {
        private final int statusCode;
        private GeminiCallException(int statusCode, String message) { super(message); this.statusCode = statusCode; }
    }
}
