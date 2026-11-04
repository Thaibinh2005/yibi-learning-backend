package com.yibi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.dto.admin.AiQuestionGenerationRequest;
import com.yibi.backend.dto.admin.AiQuestionGenerationResult;
import com.yibi.backend.dto.admin.QuestionCreateRequest;
import com.yibi.backend.dto.admin.QuestionUpdateRequest;
import com.yibi.backend.exception.AppException;
import com.yibi.backend.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Asks Gemini for a batch of questions at one HSK level, grounded in the imported syllabus word list,
 * and saves the usable ones as DRAFT through {@link QuestionBankAdminService}. A person or the admin
 * review still moves them to PUBLISHED.
 */
@Slf4j
@Service
public class AiQuestionGeneratorService {

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int SAMPLE_WORDS = 40;
    private static final List<String> LEVELS = List.of("1", "2", "3", "4", "5", "6", "7-9");
    /** Catches corrupted output such as "短an" where a Chinese word was cut and ASCII letters took its place. */
    private static final java.util.regex.Pattern CJK_NEXT_TO_LATIN =
            java.util.regex.Pattern.compile("[\u4e00-\u9fff][A-Za-z]|[A-Za-z][\u4e00-\u9fff]");
    private static final java.util.regex.Pattern STRAY_SPACE =
            java.util.regex.Pattern.compile("[\\u4e00-\\u9fff][ \\u3000]+[\\u4e00-\\u9fff]");
    private static final java.util.regex.Pattern LATIN = java.util.regex.Pattern.compile("[A-Za-z]");
    private static final java.util.regex.Pattern FRAGMENT_LINE =
            java.util.regex.Pattern.compile("^\\s*(\\d+)[.)]\\s*(.+?)\\s*$", java.util.regex.Pattern.MULTILINE);
    private static final java.util.regex.Pattern ORDER_ANSWER = java.util.regex.Pattern.compile("^[\\d,\\s\u3001]+$");
    private static final java.util.regex.Pattern NON_CJK = java.util.regex.Pattern.compile("[^\\u4e00-\\u9fff]");
    private static final java.util.regex.Pattern SPEAKER_CLAIM = java.util.regex.Pattern.compile(
            "(?i)ng\u01b0\u1eddi nam|ng\u01b0\u1eddi n\u1eef|nam thanh ni\u00ean|n\u1eef thanh ni\u00ean|\u0111\u00e0n \u00f4ng|ph\u1ee5 n\u1eef|c\u00f4 g\u00e1i|ch\u00e0ng trai");
    private static final java.util.regex.Pattern GENDER_HINT =
            java.util.regex.Pattern.compile("[\u7537\u5973\u5148\u751f\u5c0f\u59d0\u4ed6\u5979\u7238\u5988\u54e5\u59d0\u5f1f\u59b9\u7237\u5976]");
    private static final Set<String> LISTENING_TYPES = Set.of("LISTEN_WORD", "LISTEN_SENTENCE", "LISTEN_DIALOGUE");
    /** Types whose target word must come from the syllabus list at or below the requested level. */
    private static final Set<String> WORD_TYPES = Set.of("VOCAB_MEANING", "MEANING_HANZI", "PHONETICS_SELECT");
    private static final Map<String, Set<String>> SKILLS_BY_TYPE = Map.ofEntries(
            Map.entry("VOCAB_MEANING", Set.of("vocabulary")),
            Map.entry("MEANING_HANZI", Set.of("vocabulary", "characters")),
            Map.entry("PHONETICS_SELECT", Set.of("phonetics")),
            Map.entry("CLOZE", Set.of("grammar", "writing")),
            Map.entry("GRAMMAR_SELECT", Set.of("grammar")),
            Map.entry("SENTENCE_ORDER", Set.of("writing")),
            Map.entry("ERROR_CORRECTION", Set.of("grammar", "writing")),
            Map.entry("LISTEN_WORD", Set.of("listening")),
            Map.entry("LISTEN_SENTENCE", Set.of("listening")),
            Map.entry("LISTEN_DIALOGUE", Set.of("listening")),
            Map.entry("READING_COMPREHENSION", Set.of("reading")),
            Map.entry("TRANSLATION", Set.of("translation")),
            Map.entry("KEYWORD_SENTENCE", Set.of("writing")),
            Map.entry("READ_ALOUD", Set.of("speaking")));
    private static final Map<String, String> TYPE_RULES = Map.ofEntries(
            Map.entry("VOCAB_MEANING", "Hỏi nghĩa tiếng Việt của một từ tiếng Trung trong prompt; bốn lựa chọn là nghĩa tiếng Việt."),
            Map.entry("MEANING_HANZI", "Cho nghĩa tiếng Việt trong prompt; bốn lựa chọn là từ tiếng Trung (chữ Hán)."),
            Map.entry("PHONETICS_SELECT", "Cho một từ chữ Hán trong prompt; bốn lựa chọn là pinyin có dấu thanh, chỉ một cách đọc đúng."),
            Map.entry("CLOZE", "Một câu tiếng Trung có chỗ trống ____ trong prompt; bốn lựa chọn là từ điền vào, chỉ một từ làm câu đúng."),
            Map.entry("GRAMMAR_SELECT", "Kiểm tra một điểm ngữ pháp của cấp này; prompt là câu hỏi hoặc câu có chỗ trống, bốn lựa chọn là cách dùng."),
            Map.entry("SENTENCE_ORDER", "Prompt liệt kê các cụm từ bị xáo trộn; bốn lựa chọn là bốn thứ tự sắp xếp, chỉ một thứ tự tạo câu đúng."),
            Map.entry("ERROR_CORRECTION", "Prompt có một câu tiếng Trung chứa đúng một lỗi; bốn lựa chọn là các cách sửa, chỉ một cách đúng."),
            Map.entry("LISTEN_WORD", "Người học nghe một từ. audio_text là từ được đọc; prompt là câu lệnh tiếng Việt, KHÔNG chứa từ đó; bốn lựa chọn là nghĩa hoặc chữ Hán."),
            Map.entry("LISTEN_SENTENCE", "Người học nghe một câu. audio_text là câu được đọc; prompt là câu hỏi tiếng Việt về nội dung câu, KHÔNG chép lại câu; bốn lựa chọn là đáp án."),
            Map.entry("LISTEN_DIALOGUE", "Người học nghe một đoạn hội thoại ngắn hai người. audio_text là hội thoại (mỗi lượt lời một dòng, A: và B:); prompt là câu hỏi tiếng Việt về nội dung, KHÔNG chép lại hội thoại."),
            Map.entry("READING_COMPREHENSION", "Prompt gồm một đoạn đọc ngắn tiếng Trung đúng cấp, rồi một câu hỏi tiếng Việt về đoạn đó; bốn lựa chọn là đáp án."),
            Map.entry("TRANSLATION", "Prompt là một câu tiếng Trung; bốn lựa chọn là bản dịch tiếng Việt, chỉ một bản đúng nghĩa."),
            Map.entry("KEYWORD_SENTENCE", "Prompt cho một từ khóa; bốn lựa chọn là bốn câu tiếng Trung, chỉ một câu dùng từ khóa đúng."),
            Map.entry("READ_ALOUD", "Không có lựa chọn. answer_text là một câu tiếng Trung ngắn, đúng cấp, để người học đọc thành tiếng; prompt là lời nhắc tiếng Việt kèm CHÍNH câu tiếng Trung đó (chép y nguyên answer_text), ví dụ: Hãy đọc to câu sau: 我很喜欢周末去旅游。 Không đưa câu tiếng Việt để dịch."));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final QuestionBankAdminService adminService;
    private final HttpClient httpClient;
    private final List<String> apiKeys;
    private final List<String> models;

    public AiQuestionGeneratorService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            QuestionBankAdminService adminService,
            @Value("${GEMINI_API_KEY:}") String primaryKey,
            @Value("${GEMINI_API_KEY_FALLBACK_1:}") String fallbackKey1,
            @Value("${GEMINI_API_KEY_FALLBACK_2:}") String fallbackKey2,
            @Value("${app.gemini.model:gemini-3.8-flash}") String primaryModel,
            @Value("${app.gemini.fallback-models:gemini-3.6-flash,gemini-3.5-flash-lite}") String fallbackModels) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.adminService = adminService;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.apiKeys = distinctNonBlank(List.of(primaryKey, fallbackKey1, fallbackKey2));
        List<String> configured = new ArrayList<>();
        configured.add(primaryModel);
        configured.addAll(Arrays.asList(fallbackModels.split(",")));
        List<String> resolved = distinctNonBlank(configured);
        this.models = resolved.isEmpty() ? List.of("gemini-3.8-flash") : resolved;
    }

    public AiQuestionGenerationResult generate(AiQuestionGenerationRequest request) {
        String level = request.getLevelCode().trim();
        String type = request.getQuestionType().trim().toUpperCase();
        String skill = request.getSkillKey().trim().toLowerCase();
        if (!LEVELS.contains(level)) throw new BadRequestException("Unknown level: " + level);
        Set<String> skills = SKILLS_BY_TYPE.get(type);
        if (skills == null) throw new BadRequestException("Unknown question type: " + type);
        if (!skills.contains(skill)) {
            throw new BadRequestException(type + " measures " + String.join(" or ", skills) + ", not " + skill);
        }
        if (apiKeys.isEmpty()) {
            throw new AppException("Gemini API key is not configured; set GEMINI_API_KEY.", HttpStatus.SERVICE_UNAVAILABLE);
        }
        int count = request.getCount() != null ? request.getCount() : 5;
        int difficulty = request.getDifficulty() != null ? request.getDifficulty() : 2;

        List<String> sampleWords = sampleWords(level);
        if (sampleWords.isEmpty()) {
            throw new BadRequestException("No syllabus words are imported for level " + level + "; run --import-syllabus first.");
        }
        String prompt = buildPrompt(level, skill, type, request.getTopicKey(), difficulty, count, sampleWords);
        GeminiBatch batch = callWithFallback(prompt, type);

        List<Long> created = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        int index = 0;
        for (JsonNode q : batch.questions) {
            index++;
            try {
                ((com.fasterxml.jackson.databind.node.ObjectNode) q).put("__level", level);
                Long id = adminService.createQuestion(toCreateRequest(q, level, skill, type, difficulty, request.getTopicKey())).getId();
                linkToSyllabusWord(id, q.path("target_word").asText("").trim(), level);
                created.add(id);
            } catch (BadRequestException ex) {
                log.warn("AI question #{} rejected ({}): {}", index, ex.getMessage(), abbreviate(q.toString()));
                rejected.add("#" + index + ": " + ex.getMessage());
            }
        }
        log.info("AI generator level={} type={} requested={} created={} rejected={}", level, type, count, created.size(), rejected.size());
        return AiQuestionGenerationResult.builder()
                .requested(count).model(batch.model).createdIds(created).rejected(rejected).build();
    }

    private QuestionCreateRequest toCreateRequest(JsonNode q, String level, String skill, String type,
                                                  int difficulty, String requestedTopic) {
        String stem = q.path("prompt").asText("");
        requireCleanOutput(q, type, stem);
        if (CJK_NEXT_TO_LATIN.matcher(stem).find()) {
            throw new BadRequestException("prompt mixes Chinese characters and Latin letters without a space");
        }
        String readAloudText = q.path("answer_text").asText("").trim();
        if ("READ_ALOUD".equals(type) && (readAloudText.isEmpty() || !stem.contains(readAloudText))) {
            throw new BadRequestException("read-aloud prompt does not contain the sentence to read");
        }
        String targetWord = q.path("target_word").asText("").trim();
        if (WORD_TYPES.contains(type)) {
            Integer wordLevel = lowestSyllabusLevel(targetWord);
            if (wordLevel == null) throw new BadRequestException("target word '" + targetWord + "' is not in the syllabus list");
            if (wordLevel > levelNumber(level)) throw new BadRequestException("target word '" + targetWord + "' is above level " + level);
        }
        List<QuestionUpdateRequest.ChoiceUpdateRequest> choices = new ArrayList<>();
        for (JsonNode c : q.path("choices")) {
            choices.add(QuestionUpdateRequest.ChoiceUpdateRequest.builder()
                    .key(c.path("key").asText()).text(c.path("text").asText().trim()).correct(c.path("correct").asBoolean()).build());
        }
        String topic = q.path("topic_key").asText("").trim();
        if (topic.isBlank()) topic = requestedTopic;
        String audioUrl = null;
        if (LISTENING_TYPES.contains(type)) {
            String audioText = q.path("audio_text").asText("").trim();
            if (audioText.isBlank()) throw new BadRequestException("listening question without audio_text");
            // No stored audio files yet: the client reads this text aloud with speech synthesis.
            audioUrl = "tts:" + audioText;
        }
        return QuestionCreateRequest.builder()
                .questionType(type).skillKey(skill).levelCode(level).topicKey(topic)
                .prompt(q.path("prompt").asText("").trim())
                .promptPinyin("VOCAB_MEANING".equals(type) ? syllabusPinyin(targetWord) : null)
                .promptAudioUrl(audioUrl)
                .explanation(textOrNull(q.path("explanation")))
                .difficulty(difficulty).origin("AI_GENERATED")
                .answerText(textOrNull(q.path("answer_text")))
                .choices(choices).build();
    }

    /** Rules learned from reviewing the first 497 generated questions; each one rejected real defects. */
    private void requireCleanOutput(JsonNode q, String type, String stem) {
        String audioText = q.path("audio_text").asText("");
        String answerText = q.path("answer_text").asText("");
        List<String> chinese = new ArrayList<>(List.of(stem));
        if (LISTENING_TYPES.contains(type)) chinese.add(audioText);
        if ("READ_ALOUD".equals(type)) chinese.add(answerText);
        for (JsonNode c : q.path("choices")) chinese.add(c.path("text").asText(""));
        for (String t : chinese) {
            if (STRAY_SPACE.matcher(t).find()) throw new BadRequestException("a space splits a Chinese word");
        }
        if ((LISTENING_TYPES.contains(type) && LATIN.matcher(audioText).find())
                || ("READ_ALOUD".equals(type) && LATIN.matcher(answerText).find())) {
            throw new BadRequestException("Latin letters inside the Chinese audio or read-aloud text");
        }
        if (LISTENING_TYPES.contains(type) && SPEAKER_CLAIM.matcher(stem).find() && !GENDER_HINT.matcher(audioText).find()) {
            throw new BadRequestException("the question assumes a speaker's gender that the audio does not state");
        }
        if (("CLOZE".equals(type) || "GRAMMAR_SELECT".equals(type)) && !stem.contains("_")) {
            throw new BadRequestException("no blank (____) in the question");
        }
        if ("SENTENCE_ORDER".equals(type)) requireFragmentsUsed(q, stem);
        String flat = stem.replaceAll("\\s+", "");
        Integer same = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM question_bank_items
                WHERE level_code = ? AND question_type = ? AND status <> 'REJECTED'
                  AND regexp_replace(prompt, '\\s+', '', 'g') = ?
                """, Integer.class, levelOf(q), type, flat);
        if (same != null && same > 0) throw new BadRequestException("a question with the same prompt already exists");
    }

    private static String levelOf(JsonNode q) {
        return q.path("__level").asText("");
    }

    /** The correct option must use every listed fragment exactly once. */
    private void requireFragmentsUsed(JsonNode q, String stem) {
        List<String> fragments = new ArrayList<>();
        java.util.regex.Matcher m = FRAGMENT_LINE.matcher(stem);
        while (m.find()) fragments.add(m.group(2));
        if (fragments.size() < 2) {
            String list = stem.substring(stem.lastIndexOf(':') + 1);
            for (String part : list.split("[/\\uff0f|\\u3001]")) if (!part.isBlank()) fragments.add(part.trim());
        }
        if (fragments.size() < 2) throw new BadRequestException("cannot read the fragments to order");
        String correct = null;
        for (JsonNode c : q.path("choices")) {
            if (c.path("correct").asBoolean()) correct = c.path("text").asText("").trim();
        }
        if (correct == null) return;
        if (ORDER_ANSWER.matcher(correct).matches()) {
            List<Integer> numbers = new ArrayList<>();
            for (String part : correct.split("[,\\s\u3001]+")) if (!part.isBlank()) numbers.add(Integer.parseInt(part));
            Set<Integer> expected = new HashSet<>();
            for (int i = 1; i <= fragments.size(); i++) expected.add(i);
            if (numbers.size() != fragments.size() || !new HashSet<>(numbers).equals(expected)) {
                throw new BadRequestException("the correct order does not use every fragment once");
            }
            return;
        }
        char[] want = NON_CJK.matcher(String.join("", fragments)).replaceAll("").toCharArray();
        char[] have = NON_CJK.matcher(correct).replaceAll("").toCharArray();
        java.util.Arrays.sort(want);
        java.util.Arrays.sort(have);
        if (!java.util.Arrays.equals(want, have)) throw new BadRequestException("the correct sentence does not use the fragments exactly once");
    }

    /** Records which syllabus word a question tests, so a missed question can put that word in the review deck. */
    private void linkToSyllabusWord(Long questionId, String word, String level) {
        if (word.isBlank()) return;
        List<Long> records = jdbcTemplate.queryForList("""
                SELECT raw_record_id FROM hsk_vocabulary_records
                WHERE simplified = ? AND CASE level_code WHEN '7-9' THEN 8 ELSE level_code::int END <= ?
                ORDER BY CASE level_code WHEN '7-9' THEN 8 ELSE level_code::int END DESC LIMIT 1
                """, Long.class, word, levelNumber(level));
        if (records.isEmpty()) return;
        jdbcTemplate.update("""
                INSERT INTO question_bank_sources (question_id, raw_record_id, attribution_note)
                VALUES (?, ?, 'target word of the generated question') ON CONFLICT DO NOTHING
                """, questionId, records.get(0));
    }

    private List<String> sampleWords(String level) {
        return jdbcTemplate.query("""
                SELECT vr.simplified || ' (' || rd.pinyin || COALESCE(', ' || vr.part_of_speech, '') || ')' AS entry
                FROM hsk_vocabulary_records vr
                JOIN hsk_vocabulary_readings rd ON rd.raw_record_id = vr.raw_record_id AND rd.reading_order = 1
                WHERE vr.level_code = ?
                ORDER BY random() LIMIT ?
                """, (rs, i) -> rs.getString("entry"), level, SAMPLE_WORDS);
    }

    /**
     * Pinyin comes from the syllabus list, not the model: Gemini's own pinyin for a prompt was unreliable.
     * Only the vocabulary type shows it; the other types would give the answer away or need sentence pinyin.
     */
    private String syllabusPinyin(String word) {
        if (word.isBlank()) return null;
        List<String> found = jdbcTemplate.queryForList("""
                SELECT rd.pinyin FROM hsk_vocabulary_records vr
                JOIN hsk_vocabulary_readings rd ON rd.raw_record_id = vr.raw_record_id AND rd.reading_order = 1
                WHERE vr.simplified = ? ORDER BY vr.level_code LIMIT 1
                """, String.class, word);
        return found.isEmpty() ? null : found.get(0);
    }

    /** The lowest level number at which the syllabus lists this word, or null when it is not listed. */
    private Integer lowestSyllabusLevel(String word) {
        if (word.isBlank()) return null;
        return jdbcTemplate.queryForObject("""
                SELECT MIN(CASE level_code WHEN '7-9' THEN 8 ELSE level_code::int END)
                FROM hsk_vocabulary_records WHERE simplified = ?
                """, Integer.class, word);
    }

    private static int levelNumber(String level) {
        return "7-9".equals(level) ? 8 : Integer.parseInt(level);
    }

    private String buildPrompt(String level, String skill, String type, String topic, int difficulty,
                               int count, List<String> sampleWords) {
        String rules = TYPE_RULES.get(type);
        return """
                Bạn là giáo viên tiếng Trung thiết kế câu hỏi xếp lớp cho người Việt theo đề cương HSK 3.0 chính thức.
                Hãy tạo đúng %d câu hỏi dạng %s, kỹ năng %s, cấp độ HSK %s, độ khó %d trên thang 1 đến 5%s.

                QUY TẮC DẠNG CÂU: %s

                QUY TẮC CHUNG:
                - Mọi từ tiếng Trung phải thuộc đề cương HSK ở cấp %s hoặc thấp hơn. Danh sách mẫu từ cấp %s (không phải toàn bộ): %s.
                - Không dùng từ ngoài đề cương hoặc từ cao cấp hơn. Chữ Hán giản thể; pinyin có dấu thanh.
                - Lời hướng dẫn, nghĩa và giải thích viết bằng tiếng Việt.
                - Với câu trắc nghiệm: đúng bốn lựa chọn khóa A, B, C, D, khác nhau, đúng duy nhất một lựa chọn có correct=true, các lựa chọn sai hợp lý nhưng chắc chắn sai.
                - Mỗi câu có target_word: một từ chính được kiểm tra, chép đúng từ danh sách đề cương (chữ Hán), và topic_key ngắn viết thường bằng ASCII, ví dụ family_members.
                - Mỗi câu phải khác nhau, không lặp từ mục tiêu trong cùng lô.
                - Mọi lời hướng dẫn và câu hỏi viết bằng tiếng Việt, kể cả ở HSK 1 và 2: không dùng câu lệnh tiếng Trung như 选择 hoặc 阅读短文.
                - Câu nghe: không nêu giới tính, tuổi hay nghề của người nói trừ khi audio_text nói rõ; hỏi về nội dung được nói. audio_text chỉ gồm chữ Hán và dấu câu, không có chữ Latin, không có khoảng trắng giữa các chữ Hán.
                - Câu điền chỗ trống phải có đúng một chỗ trống ghi là ____.
                - Câu sắp xếp: đáp án đúng phải dùng đủ mỗi mảnh đúng một lần, và chỉ có một thứ tự tạo câu đúng.
                - explanation: một hoặc hai câu tiếng Việt giải thích vì sao đáp án đúng.
                - Với câu điền chỗ trống hoặc chọn từ: đảm bảo CHỈ MỘT lựa chọn làm câu đúng ngữ pháp và đúng nghĩa; hãy thử từng lựa chọn sai vào chỗ trống để chắc nó sai.
                - Kiểm tra lại đáp án đúng trước khi trả về. Nếu không chắc một câu, đừng tạo câu đó.
                """.formatted(count, type, skill, level, difficulty,
                topic != null && !topic.isBlank() ? ", chủ đề " + topic.trim() : "",
                rules, level, level, String.join("; ", sampleWords));
    }

    private Map<String, Object> responseSchema() {
        Map<String, Object> choice = Map.of(
                "type", "object",
                "properties", Map.of("key", Map.of("type", "string"), "text", Map.of("type", "string"),
                        "correct", Map.of("type", "boolean")),
                "required", List.of("key", "text", "correct"));
        Map<String, Object> question = Map.of(
                "type", "object",
                "properties", Map.of(
                        "prompt", Map.of("type", "string"),
                                                "audio_text", Map.of("type", "string"),
                        "choices", Map.of("type", "array", "items", choice),
                        "answer_text", Map.of("type", "string"),
                        "explanation", Map.of("type", "string"),
                        "topic_key", Map.of("type", "string"),
                        "target_word", Map.of("type", "string")),
                "required", List.of("prompt", "explanation", "topic_key", "target_word"));
        return Map.of("type", "object",
                "properties", Map.of("questions", Map.of("type", "array", "items", question)),
                "required", List.of("questions"));
    }

    private GeminiBatch callWithFallback(String prompt, String type) {
        List<String> failures = new ArrayList<>();
        for (String model : models) {
            for (String apiKey : apiKeys) {
                try {
                    JsonNode response = call(model, apiKey, prompt);
                    String text = response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
                    if (text.isBlank()) {
                        failures.add(model + ": empty response");
                        continue;
                    }
                    JsonNode questions = objectMapper.readTree(stripCodeFence(text)).path("questions");
                    if (!questions.isArray() || questions.isEmpty()) {
                        failures.add(model + ": no questions");
                        continue;
                    }
                    return new GeminiBatch(model, questions);
                } catch (GeminiStatusException ex) {
                    failures.add(model + ": HTTP " + ex.status);
                    // Quota and auth errors are key-scoped; anything else is model-scoped.
                    if (ex.status != 429 && ex.status != 401 && ex.status != 403) break;
                } catch (IOException ex) {
                    log.warn("Gemini {} returned unreadable JSON: {}", model, ex.getMessage());
                    failures.add(model + ": unreadable response");
                    break;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AppException("Gemini request was interrupted.", HttpStatus.SERVICE_UNAVAILABLE);
                }
            }
        }
        throw new AppException("Gemini did not return questions (" + String.join(", ", failures)
                + "). Check the quota and API key, then try again.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private JsonNode call(String model, String apiKey, String prompt) throws IOException, InterruptedException {
        if (!model.matches("[A-Za-z0-9._-]+")) throw new GeminiStatusException(400);
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "responseFormat", Map.of("text", Map.of("mimeType", "APPLICATION_JSON", "schema", responseSchema())),
                        "temperature", 0.5));
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(GEMINI_URL + model + ":generateContent"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Gemini {} answered HTTP {}: {}", model, response.statusCode(), abbreviate(response.body()));
            throw new GeminiStatusException(response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private static String abbreviate(String body) {
        if (body == null) return "";
        String flat = body.replaceAll("\s+", " ");
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }

    private static String textOrNull(JsonNode node) {
        String text = node.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static List<String> distinctNonBlank(Collection<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
    }

    private static String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        return trimmed.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
    }

    private record GeminiBatch(String model, JsonNode questions) {}

    private static class GeminiStatusException extends RuntimeException {
        private final int status;

        private GeminiStatusException(int status) {
            super("Gemini request failed");
            this.status = status;
        }
    }
}
