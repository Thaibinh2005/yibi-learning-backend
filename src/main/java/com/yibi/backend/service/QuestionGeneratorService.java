package com.yibi.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.dto.admin.GeneratedQuestionPreview;
import com.yibi.backend.dto.admin.GeneratedQuestionPreview.ChoicePreview;
import com.yibi.backend.dto.admin.GeneratorRunResult;
import com.yibi.backend.dto.admin.QuestionGeneratorParams;
import com.yibi.backend.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates verified multiple-choice exercises from HSK 3.0 source tables:
 * hsk_vocabulary_records, hsk_vocabulary_readings, and hsk_vocabulary_senses.
 *
 * <p>Enforced guarantees:
 * <ul>
 *   <li>Per-question transactions using {@link TransactionTemplate} so failed items roll back cleanly without aborting subsequent inserts.</li>
 *   <li>Only monophonic words (exactly 1 reading) are used for PHONETICS_SELECT to avoid dual-answer ambiguity.</li>
 *   <li>Pinyin comparisons are normalized (NFC, lowercase, stripped spaces and apostrophes).</li>
 *   <li>Senses are strictly filtered on language_code = 'vi'.</li>
 *   <li>Distractor pools cover the entire level and filter out synonyms and homophones.</li>
 *   <li>Incremental generation: candidates exclude source records that already have questions of the same type and level.</li>
 *   <li>CLOZE and GRAMMAR_SELECT are safely postponed since HSK grammar records provide taxonomy lists rather than verified sentence corpora.</li>
 *   <li>Deterministic per-question random seed: new Random(Objects.hash(type, level, rawId)).</li>
 *   <li>New questions are stored in DRAFT status with content_hash for idempotency.</li>
 * </ul>
 */
@Slf4j
@Service
public class QuestionGeneratorService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    private static final String STANDARD_CODE = "NEW_HSK";
    private static final String VERSION_CODE  = "2025";
    private static final int    NUM_CHOICES   = 4;

    public QuestionGeneratorService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    public GeneratorRunResult generate(QuestionGeneratorParams params) {
        validateParams(params);

        List<GeneratedQuestionPreview> previews = switch (params.getQuestionType()) {
            case "PHONETICS_SELECT" -> generatePhoneticsSelect(params.getLevelCode(), params.getLimit());
            case "MEANING_HANZI"    -> generateMeaningHanzi(params.getLevelCode(), params.getLimit());
            case "VOCAB_MEANING"    -> generateVocabMeaning(params.getLevelCode(), params.getLimit());
            case "CLOZE", "GRAMMAR_SELECT" -> postponeGrammarTypes(params.getQuestionType(), params.getLevelCode(), params.getLimit());
            default -> throw new BadRequestException("Unsupported question type: " + params.getQuestionType());
        };

        int validCount  = 0;
        int skippedCount = 0;
        for (GeneratedQuestionPreview p : previews) {
            if (p.getSkipReason() == null && "PASS".equals(p.getValidationStatus())) {
                validCount++;
            } else {
                skippedCount++;
            }
        }

        int imported       = 0;
        int alreadyExisted = 0;
        int failedGate     = 0;
        int failedInsert   = 0;

        if ("import".equals(params.getMode())) {
            ImportStats stats = persistAll(previews);
            imported       = stats.imported;
            alreadyExisted = stats.alreadyExisted;
            failedGate     = stats.failedGate;
            failedInsert   = stats.failedInsert;
        }

        return GeneratorRunResult.builder()
                .mode(params.getMode())
                .levelCode(params.getLevelCode())
                .questionType(params.getQuestionType())
                .requestedLimit(params.getLimit())
                .generated(validCount)
                .skipped(skippedCount)
                .failedGate(failedGate)
                .imported(imported)
                .alreadyExisted(alreadyExisted)
                .failedInsert(failedInsert)
                .questions(previews)
                .build();
    }

    private void validateParams(QuestionGeneratorParams params) {
        if (params == null) {
            throw new BadRequestException("Generator parameters must not be null");
        }
        if (params.getLevelCode() == null || !params.getLevelCode().matches("^(1|2|3|4|5|6|7-9)$")) {
            throw new BadRequestException("Invalid levelCode: must be 1-6 or 7-9");
        }
        if (params.getQuestionType() == null || !params.getQuestionType().matches("^(PHONETICS_SELECT|MEANING_HANZI|VOCAB_MEANING|CLOZE|GRAMMAR_SELECT)$")) {
            throw new BadRequestException("Invalid questionType: " + params.getQuestionType());
        }
        if (params.getLimit() < 1 || params.getLimit() > 200) {
            throw new BadRequestException("Limit must be between 1 and 200");
        }
        if (params.getMode() == null || (!"dry-run".equals(params.getMode()) && !"import".equals(params.getMode()))) {
            throw new BadRequestException("Mode must be 'dry-run' or 'import'");
        }
    }

    // -----------------------------------------------------------------------
    // Dạng 1: PHONETICS_SELECT
    //   Prompt  : chữ Hán (simplified)
    //   Choices : 4 pinyin (1 đúng, 3 nhiễu cùng độ dài từ & khác cách đọc)
    // -----------------------------------------------------------------------

    private List<GeneratedQuestionPreview> generatePhoneticsSelect(String level, int limit) {
        // Chỉ lấy từ có DUY NHẤT 1 reading và chưa từng được tạo câu hỏi PHONETICS_SELECT ở cấp này
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList("""
                SELECT
                    vr.raw_record_id,
                    vr.simplified,
                    rd.pinyin
                FROM hsk_vocabulary_records vr
                JOIN hsk_vocabulary_readings rd ON rd.raw_record_id = vr.raw_record_id
                JOIN hsk_raw_records rr ON rr.id = vr.raw_record_id
                WHERE vr.level_code = ?
                  AND rr.validation_status IN ('UNREVIEWED', 'VALID')
                  AND rd.pinyin IS NOT NULL AND TRIM(rd.pinyin) <> ''
                  AND vr.simplified IS NOT NULL AND TRIM(vr.simplified) <> ''
                  AND (
                      SELECT COUNT(*)
                      FROM hsk_vocabulary_readings r2
                      WHERE r2.raw_record_id = vr.raw_record_id
                  ) = 1
                  AND vr.raw_record_id NOT IN (
                      SELECT qs.raw_record_id
                      FROM question_bank_sources qs
                      JOIN question_bank_items qi ON qi.id = qs.question_id
                      WHERE qi.question_type = 'PHONETICS_SELECT'
                        AND qi.level_code = ?
                        AND qs.raw_record_id IS NOT NULL
                  )
                ORDER BY vr.raw_record_id
                LIMIT ?
                """, level, level, limit * 3);

        // Pool toàn bộ pinyin cùng cấp (kèm simplified để đo độ dài số âm tiết)
        List<Map<String, Object>> poolRows = jdbcTemplate.queryForList("""
                SELECT DISTINCT vr.simplified, rd.pinyin
                FROM hsk_vocabulary_readings rd
                JOIN hsk_vocabulary_records vr ON vr.raw_record_id = rd.raw_record_id
                JOIN hsk_raw_records rr ON rr.id = vr.raw_record_id
                WHERE vr.level_code = ?
                  AND rr.validation_status IN ('UNREVIEWED', 'VALID')
                  AND rd.pinyin IS NOT NULL AND TRIM(rd.pinyin) <> ''
                  AND vr.simplified IS NOT NULL AND TRIM(vr.simplified) <> ''
                """, level);

        List<PinyinPoolItem> pool = poolRows.stream()
                .map(r -> new PinyinPoolItem(str(r.get("simplified")), str(r.get("pinyin"))))
                .filter(p -> !p.pinyin.isBlank() && !p.simplified.isBlank())
                .toList();

        List<GeneratedQuestionPreview> results = new ArrayList<>();
        int validCount = 0;

        for (Map<String, Object> row : candidates) {
            if (validCount >= limit) break;

            String simplified = str(row.get("simplified"));
            String correctPin = str(row.get("pinyin"));
            Long   rawId      = toLong(row.get("raw_record_id"));

            if (simplified.isBlank() || correctPin.isBlank()) {
                results.add(skip(rawId, simplified, "PHONETICS_SELECT", level, "phonetics",
                        "Missing simplified or pinyin"));
                continue;
            }

            String normCorrect = normalizePinyin(correctPin);
            int targetLength = simplified.length();

            // Tìm distractors: cùng số lượng chữ Hán (độ dài âm tiết tương đương),
            // khác với pinyin chuẩn hóa của từ đúng.
            Map<String, String> distinctDistractors = new LinkedHashMap<>();
            Random rng = new Random(Objects.hash("PHONETICS_SELECT", level, rawId));

            List<PinyinPoolItem> shuffledPool = new ArrayList<>(pool);
            Collections.shuffle(shuffledPool, rng);

            for (PinyinPoolItem item : shuffledPool) {
                if (item.simplified.length() != targetLength) continue;

                String normPinyin = normalizePinyin(item.pinyin);
                if (normPinyin.equals(normCorrect)) continue;
                if (item.simplified.equals(simplified)) continue;

                distinctDistractors.putIfAbsent(normPinyin, item.pinyin);
                if (distinctDistractors.size() >= NUM_CHOICES - 1) break;
            }

            if (distinctDistractors.size() < NUM_CHOICES - 1) {
                results.add(skip(rawId, simplified, "PHONETICS_SELECT", level, "phonetics",
                        "Insufficient same-length pinyin distractors (found " + distinctDistractors.size() + ")"));
                continue;
            }

            List<String> choiceTexts = new ArrayList<>(distinctDistractors.values());
            choiceTexts.add(correctPin);
            Collections.shuffle(choiceTexts, rng);

            String prompt      = simplified;
            String explanation = simplified + " đọc là " + correctPin + ".";
            String code        = questionCode("PHONETICS_SELECT", rawId, level);
            String contentHash = contentHash("PHONETICS_SELECT", level, prompt, correctPin);

            GeneratedQuestionPreview preview = buildPreview(
                    code, "PHONETICS_SELECT", level, "phonetics", "pronunciation_general",
                    prompt, correctPin, null, choiceTexts, correctPin, explanation, rawId, contentHash);

            results.add(preview);
            if ("PASS".equals(preview.getValidationStatus())) {
                validCount++;
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Dạng 2: MEANING_HANZI
    //   Prompt  : pinyin + nghĩa tiếng Việt
    //   Choices : 4 chữ Hán (1 đúng, 3 nhiễu cùng độ dài, không đồng âm/đồng nghĩa)
    // -----------------------------------------------------------------------

    private List<GeneratedQuestionPreview> generateMeaningHanzi(String level, int limit) {
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList("""
                SELECT
                    vr.raw_record_id,
                    vr.simplified,
                    rd.pinyin,
                    s.meaning
                FROM hsk_vocabulary_records vr
                JOIN hsk_vocabulary_readings rd ON rd.raw_record_id = vr.raw_record_id AND rd.reading_order = 1
                JOIN hsk_vocabulary_senses   s  ON s.raw_record_id  = vr.raw_record_id AND s.sense_order = 1 AND s.language_code = 'vi'
                JOIN hsk_raw_records         rr ON rr.id = vr.raw_record_id
                WHERE vr.level_code = ?
                  AND rr.validation_status IN ('UNREVIEWED', 'VALID')
                  AND s.meaning IS NOT NULL AND TRIM(s.meaning) <> ''
                  AND rd.pinyin IS NOT NULL AND TRIM(rd.pinyin) <> ''
                  AND vr.simplified IS NOT NULL AND TRIM(vr.simplified) <> ''
                  AND vr.raw_record_id NOT IN (
                      SELECT qs.raw_record_id
                      FROM question_bank_sources qs
                      JOIN question_bank_items qi ON qi.id = qs.question_id
                      WHERE qi.question_type = 'MEANING_HANZI'
                        AND qi.level_code = ?
                        AND qs.raw_record_id IS NOT NULL
                  )
                ORDER BY vr.raw_record_id
                LIMIT ?
                """, level, level, limit * 3);

        // Pool chữ Hán toàn cấp kèm reading và meaning để lọc đồng âm/đồng nghĩa
        List<Map<String, Object>> poolRows = jdbcTemplate.queryForList("""
                SELECT DISTINCT vr.simplified, rd.pinyin, s.meaning
                FROM hsk_vocabulary_records vr
                JOIN hsk_vocabulary_readings rd ON rd.raw_record_id = vr.raw_record_id AND rd.reading_order = 1
                JOIN hsk_vocabulary_senses   s  ON s.raw_record_id  = vr.raw_record_id AND s.sense_order = 1 AND s.language_code = 'vi'
                JOIN hsk_raw_records         rr ON rr.id = vr.raw_record_id
                WHERE vr.level_code = ?
                  AND rr.validation_status IN ('UNREVIEWED', 'VALID')
                  AND vr.simplified IS NOT NULL AND TRIM(vr.simplified) <> ''
                """, level);

        List<HanziPoolItem> pool = poolRows.stream()
                .map(r -> new HanziPoolItem(str(r.get("simplified")), str(r.get("pinyin")), str(r.get("meaning"))))
                .filter(h -> !h.simplified.isBlank())
                .toList();

        List<GeneratedQuestionPreview> results = new ArrayList<>();
        int validCount = 0;

        for (Map<String, Object> row : candidates) {
            if (validCount >= limit) break;

            String simplified = str(row.get("simplified"));
            String pinyin     = str(row.get("pinyin"));
            String meaning    = str(row.get("meaning"));
            Long   rawId      = toLong(row.get("raw_record_id"));

            if (simplified.isBlank() || pinyin.isBlank() || meaning.isBlank()) {
                results.add(skip(rawId, simplified, "MEANING_HANZI", level, "characters",
                        "Missing simplified, pinyin, or Vietnamese meaning"));
                continue;
            }

            String normPinyin = normalizePinyin(pinyin);
            int targetLength = simplified.length();

            Map<String, String> distinctDistractors = new LinkedHashMap<>();
            Random rng = new Random(Objects.hash("MEANING_HANZI", level, rawId));

            List<HanziPoolItem> shuffledPool = new ArrayList<>(pool);
            Collections.shuffle(shuffledPool, rng);

            for (HanziPoolItem item : shuffledPool) {
                if (item.simplified.length() != targetLength) continue;
                if (item.simplified.equals(simplified)) continue;
                // Loại từ đồng âm
                if (normalizePinyin(item.pinyin).equals(normPinyin)) continue;
                // Loại từ đồng nghĩa
                if (sharesMeaningFragment(item.meaning, meaning)) continue;

                distinctDistractors.putIfAbsent(item.simplified, item.simplified);
                if (distinctDistractors.size() >= NUM_CHOICES - 1) break;
            }

            if (distinctDistractors.size() < NUM_CHOICES - 1) {
                results.add(skip(rawId, simplified, "MEANING_HANZI", level, "characters",
                        "Insufficient Hanzi distractors of same length (found " + distinctDistractors.size() + ")"));
                continue;
            }

            List<String> choiceTexts = new ArrayList<>(distinctDistractors.values());
            choiceTexts.add(simplified);
            Collections.shuffle(choiceTexts, rng);

            String prompt      = pinyin + " (" + cleanMeaning(meaning) + ")";
            String explanation = "\"" + cleanMeaning(meaning) + "\" viết là " + simplified + " (" + pinyin + ").";
            String code        = questionCode("MEANING_HANZI", rawId, level);
            String contentHash = contentHash("MEANING_HANZI", level, prompt, simplified);

            GeneratedQuestionPreview preview = buildPreview(
                    code, "MEANING_HANZI", level, "characters", "characters_general",
                    prompt, simplified, pinyin, choiceTexts, simplified, explanation, rawId, contentHash);

            results.add(preview);
            if ("PASS".equals(preview.getValidationStatus())) {
                validCount++;
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Dạng 3: VOCAB_MEANING
    //   Prompt  : chữ Hán (kèm pinyin)
    //   Choices : 4 nghĩa tiếng Việt (1 đúng, 3 nhiễu từ từ vựng khác không trùng nghĩa)
    // -----------------------------------------------------------------------

    private List<GeneratedQuestionPreview> generateVocabMeaning(String level, int limit) {
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList("""
                SELECT
                    vr.raw_record_id,
                    vr.simplified,
                    rd.pinyin,
                    s.meaning
                FROM hsk_vocabulary_records vr
                JOIN hsk_vocabulary_readings rd ON rd.raw_record_id = vr.raw_record_id AND rd.reading_order = 1
                JOIN hsk_vocabulary_senses   s  ON s.raw_record_id  = vr.raw_record_id AND s.sense_order = 1 AND s.language_code = 'vi'
                JOIN hsk_raw_records         rr ON rr.id = vr.raw_record_id
                WHERE vr.level_code = ?
                  AND rr.validation_status IN ('UNREVIEWED', 'VALID')
                  AND s.meaning IS NOT NULL AND TRIM(s.meaning) <> ''
                  AND vr.simplified IS NOT NULL AND TRIM(vr.simplified) <> ''
                  AND vr.raw_record_id NOT IN (
                      SELECT qs.raw_record_id
                      FROM question_bank_sources qs
                      JOIN question_bank_items qi ON qi.id = qs.question_id
                      WHERE qi.question_type = 'VOCAB_MEANING'
                        AND qi.level_code = ?
                        AND qs.raw_record_id IS NOT NULL
                  )
                ORDER BY vr.raw_record_id
                LIMIT ?
                """, level, level, limit * 3);

        // Pool nghĩa tiếng Việt của toàn bộ cấp HSK
        List<Map<String, Object>> poolRows = jdbcTemplate.queryForList("""
                SELECT DISTINCT vr.simplified, s.meaning
                FROM hsk_vocabulary_senses s
                JOIN hsk_vocabulary_records vr ON vr.raw_record_id = s.raw_record_id
                JOIN hsk_raw_records rr ON rr.id = vr.raw_record_id
                WHERE vr.level_code = ?
                  AND s.language_code = 'vi'
                  AND rr.validation_status IN ('UNREVIEWED', 'VALID')
                  AND s.meaning IS NOT NULL AND TRIM(s.meaning) <> ''
                """, level);

        List<MeaningPoolItem> pool = poolRows.stream()
                .map(r -> new MeaningPoolItem(str(r.get("simplified")), str(r.get("meaning"))))
                .filter(m -> !m.meaning.isBlank() && !m.meaning.contains("(dạng er hóa)") && !m.meaning.contains("(dạng uốn lưỡi)"))
                .toList();

        List<GeneratedQuestionPreview> results = new ArrayList<>();
        int validCount = 0;

        for (Map<String, Object> row : candidates) {
            if (validCount >= limit) break;

            String simplified     = str(row.get("simplified"));
            String pinyin         = str(row.get("pinyin"));
            String correctMeaning = cleanMeaning(str(row.get("meaning")));
            Long   rawId          = toLong(row.get("raw_record_id"));

            if (simplified.isBlank() || correctMeaning.isBlank()) {
                results.add(skip(rawId, simplified, "VOCAB_MEANING", level, "vocabulary",
                        "Missing simplified or Vietnamese meaning"));
                continue;
            }

            Map<String, String> distinctDistractors = new LinkedHashMap<>();
            Random rng = new Random(Objects.hash("VOCAB_MEANING", level, rawId));

            List<MeaningPoolItem> shuffledPool = new ArrayList<>(pool);
            Collections.shuffle(shuffledPool, rng);

            int correctLen = correctMeaning.length();

            for (MeaningPoolItem item : shuffledPool) {
                // Không lấy nghĩa từ cùng chữ Hán (kể cả khác rawId)
                if (item.simplified.equals(simplified)) continue;

                String candidateMeaning = cleanMeaning(item.meaning);
                if (candidateMeaning.equalsIgnoreCase(correctMeaning)) continue;

                // Loại từ đồng nghĩa (chia sẻ bất kỳ nhánh nghĩa nào)
                if (sharesMeaningFragment(candidateMeaning, correctMeaning)) continue;

                // Ưu tiên độ dài tương đương (tránh phương án quá ngắn so với quá dài)
                int distLen = candidateMeaning.length();
                if (distLen > correctLen * 3 + 10 || correctLen > distLen * 3 + 10) continue;

                distinctDistractors.putIfAbsent(candidateMeaning.toLowerCase(Locale.ROOT), candidateMeaning);
                if (distinctDistractors.size() >= NUM_CHOICES - 1) break;
            }

            if (distinctDistractors.size() < NUM_CHOICES - 1) {
                results.add(skip(rawId, simplified, "VOCAB_MEANING", level, "vocabulary",
                        "Insufficient non-synonymous meaning distractors (found " + distinctDistractors.size() + ")"));
                continue;
            }

            List<String> choiceTexts = new ArrayList<>(distinctDistractors.values());
            choiceTexts.add(correctMeaning);
            Collections.shuffle(choiceTexts, rng);

            String prompt      = simplified + (pinyin.isBlank() ? "" : " (" + pinyin + ")");
            String explanation = simplified + " có nghĩa là \"" + correctMeaning + "\".";
            String code        = questionCode("VOCAB_MEANING", rawId, level);
            String contentHash = contentHash("VOCAB_MEANING", level, simplified, correctMeaning);

            GeneratedQuestionPreview preview = buildPreview(
                    code, "VOCAB_MEANING", level, "vocabulary", "vocabulary_general",
                    prompt, correctMeaning, pinyin.isBlank() ? null : pinyin,
                    choiceTexts, correctMeaning, explanation, rawId, contentHash);

            results.add(preview);
            if ("PASS".equals(preview.getValidationStatus())) {
                validCount++;
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Postponement of CLOZE and GRAMMAR_SELECT
    //   HSK 3.0 grammar source records contain taxonomy/lists (e.g. "小—、第—",
    //   "方位名词: 上、下、里") without verified full Chinese sentences. Generating
    //   them via heuristics produces malformed questions. We safely skip them
    //   until a verified sentence corpus is available.
    // -----------------------------------------------------------------------

    private List<GeneratedQuestionPreview> postponeGrammarTypes(String type, String level, int limit) {
        log.warn("Question type {} is postponed for level {}: HSK grammar dataset provides taxonomy lists without verified sentence corpus.", type, level);
        List<GeneratedQuestionPreview> list = new ArrayList<>();
        list.add(GeneratedQuestionPreview.builder()
                .questionCode("POSTPONED-" + type + "-" + level)
                .questionType(type)
                .levelCode(level)
                .skillKey("grammar")
                .prompt("Postponed question type")
                .skipReason("Postponed: HSK 3.0 grammar records only contain syllabus taxonomy lists without verified full Chinese sentences. Postponed until verified sentence corpus is connected.")
                .validationStatus("SKIPPED")
                .choices(List.of())
                .build());
        return list;
    }

    // -----------------------------------------------------------------------
    // Persist (import mode) with per-question transaction isolation
    // -----------------------------------------------------------------------

    public ImportStats persistAll(List<GeneratedQuestionPreview> previews) {
        int imported       = 0;
        int alreadyExisted = 0;
        int failedGate     = 0;
        int failedInsert   = 0;

        for (GeneratedQuestionPreview preview : previews) {
            if (preview.getSkipReason() != null) continue;

            if (!passesQualityGate(preview)) {
                failedGate++;
                log.warn("Quality gate failed for {}: {}", preview.getQuestionCode(), preview.getValidationStatus());
                continue;
            }

            try {
                // Execute each question in an isolated transaction.
                // If any error occurs inside, only this question rolls back cleanly.
                Boolean inserted = transactionTemplate.execute(status -> {
                    String contentHash = contentHash(
                            preview.getQuestionType(), preview.getLevelCode(),
                            preview.getPrompt(), preview.getCorrectAnswer());

                    Long questionId = insertQuestion(preview, contentHash);
                    if (questionId == null) {
                        // Conflict on content_hash (already exists)
                        return false;
                    }
                    insertChoices(questionId, preview.getChoices());
                    if (preview.getSourceRawRecordId() != null) {
                        insertSource(questionId, preview.getSourceRawRecordId());
                    }
                    return true;
                });

                if (Boolean.TRUE.equals(inserted)) {
                    imported++;
                } else {
                    alreadyExisted++;
                }
            } catch (Exception ex) {
                failedInsert++;
                log.error("Failed to insert question {}: {}", preview.getQuestionCode(), ex.getMessage(), ex);
            }
        }
        return new ImportStats(imported, alreadyExisted, failedGate, failedInsert);
    }

    private Long insertQuestion(GeneratedQuestionPreview preview, String contentHash) {
        String answerData = "{}";
        try {
            answerData = objectMapper.writeValueAsString(Map.of(
                    "correctAnswer", preview.getCorrectAnswer()));
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize answer_data", e);
        }

        List<Long> ids = jdbcTemplate.query("""
                INSERT INTO question_bank_items
                  (question_code, question_type, skill_key, standard_code, version_code,
                   level_code, topic_key, prompt, prompt_pinyin, answer_text, answer_data,
                   explanation, difficulty, origin, status, content_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'SOURCE', 'DRAFT', ?)
                ON CONFLICT (content_hash) WHERE content_hash IS NOT NULL DO NOTHING
                RETURNING id
                """,
                (rs, rowNum) -> rs.getLong("id"),
                preview.getQuestionCode(),
                preview.getQuestionType(),
                preview.getSkillKey(),
                STANDARD_CODE,
                VERSION_CODE,
                preview.getLevelCode(),
                preview.getTopicKey(),
                preview.getPrompt(),
                preview.getPromptPinyin(),
                preview.getCorrectAnswer(),
                answerData,
                preview.getExplanation(),
                difficultyFor(preview.getLevelCode()),
                contentHash);

        return ids.isEmpty() ? null : ids.get(0);
    }

    private void insertChoices(Long questionId, List<ChoicePreview> choices) {
        int order = 0;
        for (ChoicePreview c : choices) {
            jdbcTemplate.update("""
                    INSERT INTO question_bank_choices
                      (question_id, choice_key, choice_order, choice_text, is_correct)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (question_id, choice_key) DO UPDATE SET
                      choice_order = EXCLUDED.choice_order,
                      choice_text  = EXCLUDED.choice_text,
                      is_correct   = EXCLUDED.is_correct
                    """,
                    questionId, c.getKey(), ++order, c.getText(), c.isCorrect());
        }
    }

    private void insertSource(Long questionId, Long rawRecordId) {
        jdbcTemplate.update("""
                INSERT INTO question_bank_sources (question_id, raw_record_id, evidence_text)
                VALUES (?, ?, 'Auto-generated from verified HSK source record')
                ON CONFLICT (question_id, raw_record_id) WHERE raw_record_id IS NOT NULL DO NOTHING
                """, questionId, rawRecordId);
    }

    // -----------------------------------------------------------------------
    // Publish draft questions
    // -----------------------------------------------------------------------

    public int publishDraftQuestions(String levelCode, String questionType) {
        if (levelCode != null && !levelCode.isBlank() && questionType != null && !questionType.isBlank()) {
            return jdbcTemplate.update("""
                    UPDATE question_bank_items
                    SET status = 'PUBLISHED', updated_at = NOW()
                    WHERE status = 'DRAFT' AND origin = 'SOURCE'
                      AND level_code = ? AND question_type = ?
                    """, levelCode, questionType);
        } else if (levelCode != null && !levelCode.isBlank()) {
            return jdbcTemplate.update("""
                    UPDATE question_bank_items
                    SET status = 'PUBLISHED', updated_at = NOW()
                    WHERE status = 'DRAFT' AND origin = 'SOURCE'
                      AND level_code = ?
                    """, levelCode);
        } else if (questionType != null && !questionType.isBlank()) {
            return jdbcTemplate.update("""
                    UPDATE question_bank_items
                    SET status = 'PUBLISHED', updated_at = NOW()
                    WHERE status = 'DRAFT' AND origin = 'SOURCE'
                      AND question_type = ?
                    """, questionType);
        } else {
            return jdbcTemplate.update("""
                    UPDATE question_bank_items
                    SET status = 'PUBLISHED', updated_at = NOW()
                    WHERE status = 'DRAFT' AND origin = 'SOURCE'
                    """);
        }
    }

    // -----------------------------------------------------------------------
    // Quality Gate
    // -----------------------------------------------------------------------

    private boolean passesQualityGate(GeneratedQuestionPreview preview) {
        List<ChoicePreview> choices = preview.getChoices();

        if (choices == null || choices.size() != NUM_CHOICES) {
            preview.setValidationStatus("FAIL_CHOICE_COUNT");
            return false;
        }
        long correctCount = choices.stream().filter(ChoicePreview::isCorrect).count();
        if (correctCount != 1) {
            preview.setValidationStatus("FAIL_CORRECT_COUNT");
            return false;
        }

        // Kiểm tra trùng lựa chọn (áp dụng cả normalization cho pinyin)
        if ("PHONETICS_SELECT".equals(preview.getQuestionType())) {
            long distinctNorm = choices.stream()
                    .map(c -> normalizePinyin(c.getText()))
                    .distinct().count();
            if (distinctNorm != NUM_CHOICES) {
                preview.setValidationStatus("FAIL_DUPLICATE_CHOICES");
                return false;
            }
        } else {
            long distinctTexts = choices.stream()
                    .map(c -> c.getText().trim().toLowerCase(Locale.ROOT))
                    .distinct().count();
            if (distinctTexts != NUM_CHOICES) {
                preview.setValidationStatus("FAIL_DUPLICATE_CHOICES");
                return false;
            }
        }

        // Đảm bảo đáp án đúng khớp với lựa chọn is_correct = true
        ChoicePreview correctChoice = choices.stream().filter(ChoicePreview::isCorrect).findFirst().orElse(null);
        if (correctChoice == null || !correctChoice.getText().equals(preview.getCorrectAnswer())) {
            preview.setValidationStatus("FAIL_ANSWER_MISMATCH");
            return false;
        }

        if (preview.getPrompt() == null || preview.getPrompt().isBlank()) {
            preview.setValidationStatus("FAIL_EMPTY_PROMPT");
            return false;
        }
        if (preview.getLevelCode() == null || preview.getQuestionType() == null) {
            preview.setValidationStatus("FAIL_MISSING_METADATA");
            return false;
        }
        preview.setValidationStatus("PASS");
        return true;
    }

    // -----------------------------------------------------------------------
    // Normalization & Helpers
    // -----------------------------------------------------------------------

    public static String normalizePinyin(String pinyin) {
        if (pinyin == null) return "";
        // Chuyển Unicode sang chuẩn NFC
        String s = Normalizer.normalize(pinyin.trim(), Normalizer.Form.NFC);
        s = s.toLowerCase(Locale.ROOT);
        // Loại bỏ khoảng trắng, dấu nháy đơn (thẳng và cong), gạch nối, dấu chấm, gạch chéo
        return s.replaceAll("[\\s'’\\-_/·.]+", "");
    }

    private static String cleanMeaning(String meaning) {
        if (meaning == null) return "";
        // Dọn dẹp khoảng trắng thừa
        return meaning.replaceAll("\\s+", " ").trim();
    }

    private static Set<String> splitMeaningFragments(String meaning) {
        if (meaning == null || meaning.isBlank()) return Set.of();
        // Tách các phân đoạn nghĩa theo dấu chấm phẩy, phẩy, gạch chéo
        String[] parts = meaning.split("[;,/；，、\\n]+");
        Set<String> set = new HashSet<>();
        for (String p : parts) {
            String cleaned = p.replaceAll("\\([^)]*\\)", "").trim().toLowerCase(Locale.ROOT);
            if (!cleaned.isBlank()) {
                set.add(cleaned);
            }
        }
        return set;
    }

    public static boolean sharesMeaningFragment(String m1, String m2) {
        Set<String> s1 = splitMeaningFragments(m1);
        Set<String> s2 = splitMeaningFragments(m2);
        for (String token : s1) {
            if (s2.contains(token)) return true;
        }
        return false;
    }

    private GeneratedQuestionPreview buildPreview(
            String code, String qType, String level, String skill, String topic,
            String prompt, String correctAnswer, String promptPinyin,
            List<String> choiceTexts, String correctText,
            String explanation, Long rawId, String contentHash) {

        List<ChoicePreview> choices = new ArrayList<>();
        char key = 'A';
        for (String text : choiceTexts) {
            choices.add(ChoicePreview.builder()
                    .key(String.valueOf(key++))
                    .text(text)
                    .correct(text.equals(correctText))
                    .build());
        }

        GeneratedQuestionPreview preview = GeneratedQuestionPreview.builder()
                .questionCode(code)
                .questionType(qType)
                .levelCode(level)
                .skillKey(skill)
                .topicKey(topic)
                .prompt(prompt)
                .promptPinyin(promptPinyin)
                .correctAnswer(correctAnswer)
                .choices(choices)
                .explanation(explanation)
                .sourceRawRecordId(rawId)
                .build();

        passesQualityGate(preview);
        return preview;
    }

    private GeneratedQuestionPreview skip(Long rawId, String label, String qType, String level, String skill, String reason) {
        return GeneratedQuestionPreview.builder()
                .questionCode("SKIPPED-" + rawId)
                .questionType(qType)
                .levelCode(level)
                .skillKey(skill)
                .prompt(label)
                .sourceRawRecordId(rawId)
                .skipReason(reason)
                .validationStatus("SKIPPED")
                .choices(List.of())
                .build();
    }

    private String questionCode(String type, Long rawId, String level) {
        return "hsk-" + level + "-" + type.toLowerCase(Locale.ROOT).replace('_', '-') + "-" + rawId;
    }

    private String contentHash(String type, String level, String prompt, String answer) {
        String canonical = type + "|" + level + "|" + prompt.trim() + "|" + answer.trim();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static int difficultyFor(String level) {
        return switch (level) {
            case "1", "2" -> 1;
            case "3", "4" -> 2;
            case "5", "6" -> 3;
            case "7-9"    -> 4;
            default       -> throw new IllegalArgumentException("Invalid HSK level: " + level);
        };
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v).trim(); }
    private static Long   toLong(Object v) { return v == null ? null : ((Number) v).longValue(); }

    private record PinyinPoolItem(String simplified, String pinyin) {}
    private record HanziPoolItem(String simplified, String pinyin, String meaning) {}
    private record MeaningPoolItem(String simplified, String meaning) {}

    public record ImportStats(int imported, int alreadyExisted, int failedGate, int failedInsert) {}
}
