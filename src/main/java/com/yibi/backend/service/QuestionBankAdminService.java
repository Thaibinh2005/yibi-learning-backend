package com.yibi.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.dto.admin.*;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionBankAdminService {

    private static final Set<String> QUESTION_TYPES = Set.of(
            "VOCAB_MEANING", "MEANING_HANZI", "PHONETICS_SELECT", "CLOZE", "GRAMMAR_SELECT",
            "SENTENCE_ORDER", "ERROR_CORRECTION", "LISTEN_WORD", "LISTEN_SENTENCE",
            "LISTEN_DIALOGUE", "READING_COMPREHENSION", "TRANSLATION", "KEYWORD_SENTENCE", "READ_ALOUD");
    private static final Set<String> SKILLS = Set.of(
            "vocabulary", "phonetics", "characters", "grammar", "listening",
            "reading", "writing", "translation", "speaking");
    private static final List<String> LEVELS = List.of("1", "2", "3", "4", "5", "6", "7-9");
    private static final List<String> COVERAGE_SKILLS = List.of(
            "listening", "reading", "vocabulary", "grammar", "characters", "writing", "speaking");
    private static final int TARGET_PER_CELL = 8;
    private static final int SPEAKING_TARGET_PER_CELL = 4;
    /** Types answered by reading text aloud, so they carry no choices. */
    private static final Set<String> NO_CHOICE_TYPES = Set.of("READ_ALOUD");
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("REVIEWED", "PUBLISHED", "REJECTED"),
            "REVIEWED", Set.of("DRAFT", "PUBLISHED", "REJECTED"),
            "PUBLISHED", Set.of("RETIRED"),
            "RETIRED", Set.of("PUBLISHED"),
            "REJECTED", Set.of("DRAFT"));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public QuestionBankPageResponse getQuestions(
            String levelCode, String skillKey, String questionType,
            String status, String search, int page, int size) {

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);

        StringBuilder whereClause = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (levelCode != null && !levelCode.isBlank() && !"all".equalsIgnoreCase(levelCode)) {
            whereClause.append(" AND q.level_code = ? ");
            params.add(levelCode.trim());
        }

        if (skillKey != null && !skillKey.isBlank() && !"all".equalsIgnoreCase(skillKey)) {
            whereClause.append(" AND q.skill_key = ? ");
            params.add(skillKey.trim());
        }

        if (questionType != null && !questionType.isBlank() && !"all".equalsIgnoreCase(questionType)) {
            whereClause.append(" AND q.question_type = ? ");
            params.add(questionType.trim());
        }

        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            whereClause.append(" AND q.status = ? ");
            params.add(status.trim().toUpperCase());
        }

        if (search != null && !search.isBlank()) {
            whereClause.append(" AND (q.prompt ILIKE ? OR q.prompt_pinyin ILIKE ? OR q.answer_text ILIKE ? OR q.question_code ILIKE ?) ");
            String term = "%" + search.trim() + "%";
            params.add(term);
            params.add(term);
            params.add(term);
            params.add(term);
        }

        // 1. Count total elements
        String countSql = "SELECT count(*) FROM question_bank_items q " + whereClause;
        Long totalElements = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        long total = totalElements != null ? totalElements : 0L;
        int totalPages = (int) Math.ceil((double) total / safeSize);

        if (total == 0) {
            return QuestionBankPageResponse.builder()
                    .items(Collections.emptyList())
                    .totalElements(0)
                    .totalPages(0)
                    .currentPage(safePage)
                    .pageSize(safeSize)
                    .build();
        }

        // 2. Select items for current page
        String selectSql = """
                SELECT q.id, q.question_code, q.question_type, q.skill_key, q.standard_code,
                       q.version_code, q.level_code, q.topic_key, q.prompt, q.prompt_pinyin,
                       q.prompt_audio_url, q.answer_text, q.explanation, q.difficulty,
                       q.origin, q.status, q.lesson_id, q.content_hash, q.created_at, q.updated_at,
                       s.raw_record_id AS source_raw_record_id
                FROM question_bank_items q
                LEFT JOIN question_bank_sources s ON s.question_id = q.id
                """ + whereClause + " ORDER BY q.level_code, q.id DESC LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(safeSize);
        queryParams.add((long) safePage * safeSize);

        List<QuestionBankItemAdminDto> items = jdbcTemplate.query(selectSql, this::mapItemRow, queryParams.toArray());

        // 3. Batch load choices for retrieved items
        if (!items.isEmpty()) {
            List<Long> questionIds = items.stream().map(QuestionBankItemAdminDto::getId).toList();
            Map<Long, List<QuestionBankChoiceAdminDto>> choicesByQuestion = loadChoicesForQuestions(questionIds);
            for (QuestionBankItemAdminDto item : items) {
                item.setChoices(choicesByQuestion.getOrDefault(item.getId(), Collections.emptyList()));
            }
        }

        return QuestionBankPageResponse.builder()
                .items(items)
                .totalElements(total)
                .totalPages(totalPages)
                .currentPage(safePage)
                .pageSize(safeSize)
                .build();
    }

    @Transactional(readOnly = true)
    public QuestionBankItemAdminDto getQuestionById(Long id) {
        String sql = """
                SELECT q.id, q.question_code, q.question_type, q.skill_key, q.standard_code,
                       q.version_code, q.level_code, q.topic_key, q.prompt, q.prompt_pinyin,
                       q.prompt_audio_url, q.answer_text, q.explanation, q.difficulty,
                       q.origin, q.status, q.lesson_id, q.content_hash, q.created_at, q.updated_at,
                       s.raw_record_id AS source_raw_record_id
                FROM question_bank_items q
                LEFT JOIN question_bank_sources s ON s.question_id = q.id
                WHERE q.id = ?
                """;

        List<QuestionBankItemAdminDto> list = jdbcTemplate.query(sql, this::mapItemRow, id);
        if (list.isEmpty()) {
            throw new ResourceNotFoundException("QuestionBankItem", "id", id);
        }

        QuestionBankItemAdminDto item = list.get(0);
        Map<Long, List<QuestionBankChoiceAdminDto>> choices = loadChoicesForQuestions(List.of(id));
        item.setChoices(choices.getOrDefault(id, Collections.emptyList()));
        return item;
    }

    @Transactional(readOnly = true)
    public QuestionBankStatsResponse getStats() {
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM question_bank_items", Long.class);
        Long published = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM question_bank_items WHERE status = 'PUBLISHED'", Long.class);
        Long draft = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM question_bank_items WHERE status = 'DRAFT'", Long.class);
        Long withAudio = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM question_bank_items WHERE prompt_audio_url IS NOT NULL", Long.class);

        Map<String, Long> byLevel = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT COALESCE(level_code, 'UNASSIGNED') AS lvl, count(*) AS cnt
                FROM question_bank_items
                GROUP BY level_code
                ORDER BY level_code
                """, rs -> {
            byLevel.put(rs.getString("lvl"), rs.getLong("cnt"));
        });

        Map<String, Long> byType = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT question_type, count(*) AS cnt
                FROM question_bank_items
                GROUP BY question_type
                ORDER BY question_type
                """, rs -> {
            byType.put(rs.getString("question_type"), rs.getLong("cnt"));
        });

        Map<String, Long> bySkill = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT skill_key, count(*) AS cnt
                FROM question_bank_items
                GROUP BY skill_key
                ORDER BY skill_key
                """, rs -> {
            bySkill.put(rs.getString("skill_key"), rs.getLong("cnt"));
        });

        return QuestionBankStatsResponse.builder()
                .totalQuestions(total != null ? total : 0)
                .publishedCount(published != null ? published : 0)
                .draftCount(draft != null ? draft : 0)
                .withAudioCount(withAudio != null ? withAudio : 0)
                .byLevel(byLevel)
                .byType(byType)
                .bySkill(bySkill)
                .build();
    }

    @Transactional(readOnly = true)
    public PoolCoverageResponse getPoolCoverage() {
        Map<String, Integer> counts = new HashMap<>();
        jdbcTemplate.query("""
                SELECT level_code, skill_key, count(*) AS cnt
                FROM question_bank_items
                WHERE status = 'PUBLISHED' AND standard_code = 'NEW_HSK' AND version_code = '2025'
                  AND level_code IS NOT NULL
                GROUP BY level_code, skill_key
                """, rs -> {
            counts.put(rs.getString("level_code") + "|" + rs.getString("skill_key"), rs.getInt("cnt"));
        });

        List<PoolCoverageResponse.Cell> cells = new ArrayList<>();
        int totalMissing = 0;
        for (String level : LEVELS) {
            for (String skill : COVERAGE_SKILLS) {
                int target = "speaking".equals(skill) ? SPEAKING_TARGET_PER_CELL : TARGET_PER_CELL;
                int published = counts.getOrDefault(level + "|" + skill, 0);
                int missing = Math.max(0, target - published);
                totalMissing += missing;
                cells.add(PoolCoverageResponse.Cell.builder()
                        .levelCode(level).skillKey(skill)
                        .published(published).target(target).missing(missing)
                        .build());
            }
        }
        return PoolCoverageResponse.builder().ready(totalMissing == 0).totalMissing(totalMissing).cells(cells).build();
    }

    @Transactional
    public QuestionBankItemAdminDto createQuestion(QuestionCreateRequest request) {
        String type = request.getQuestionType().trim().toUpperCase();
        String skill = request.getSkillKey().trim().toLowerCase();
        String level = request.getLevelCode().trim();
        if (!QUESTION_TYPES.contains(type)) throw new BadRequestException("Unknown question type: " + type);
        if (!SKILLS.contains(skill)) throw new BadRequestException("Unknown skill: " + skill);
        if (!LEVELS.contains(level)) throw new BadRequestException("Unknown level: " + level);

        List<QuestionUpdateRequest.ChoiceUpdateRequest> choices =
                request.getChoices() != null ? request.getChoices() : List.of();
        String answerText = NO_CHOICE_TYPES.contains(type)
                ? request.getAnswerText()
                : choices.stream().filter(QuestionUpdateRequest.ChoiceUpdateRequest::isCorrect)
                        .map(QuestionUpdateRequest.ChoiceUpdateRequest::getText).findFirst().orElse(null);
        requireValidContent(type, request.getPrompt(), answerText,
                choices.stream().map(QuestionUpdateRequest.ChoiceUpdateRequest::getText).toList(),
                (int) choices.stream().filter(QuestionUpdateRequest.ChoiceUpdateRequest::isCorrect).count());

        String hash = sha256(type + "|" + level + "|" + request.getPrompt().trim() + "|" + answerText.trim());
        Integer duplicates = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM question_bank_items WHERE content_hash = ?", Integer.class, hash);
        if (duplicates != null && duplicates > 0) {
            throw new BadRequestException("A question with the same content already exists");
        }

        int difficulty = request.getDifficulty() != null ? Math.max(1, Math.min(request.getDifficulty(), 5)) : 1;
        String origin = "AI_GENERATED".equalsIgnoreCase(request.getOrigin()) ? "AI_GENERATED" : "HUMAN";
        String topic = request.getTopicKey() != null && !request.getTopicKey().isBlank() ? request.getTopicKey().trim() : "general";
        String answerDataJson;
        try {
            answerDataJson = objectMapper.writeValueAsString(Map.of("correctAnswer", answerText));
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Could not serialize the answer");
        }

        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO question_bank_items
                  (question_code, question_type, skill_key, level_code, topic_key, prompt, prompt_pinyin,
                   prompt_audio_url, answer_text, answer_data, explanation, difficulty, origin, status, content_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'DRAFT', ?) RETURNING id
                """, Long.class,
                "ADMIN-" + UUID.randomUUID(), type, skill, level, topic, request.getPrompt().trim(),
                request.getPromptPinyin(), request.getPromptAudioUrl(), answerText, answerDataJson,
                request.getExplanation(), difficulty, origin, hash);

        int order = 1;
        for (QuestionUpdateRequest.ChoiceUpdateRequest c : choices) {
            jdbcTemplate.update("""
                    INSERT INTO question_bank_choices(question_id, choice_key, choice_order, choice_text, is_correct)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, c.getKey(), order++, c.getText(), c.isCorrect());
        }
        ensureCalibration(id, level, difficulty);
        log.info("Admin created question id={} type={} level={} skill={}", id, type, level, skill);
        return getQuestionById(id);
    }

    @Transactional
    public void publishQuestion(Long id) {
        changeStatus(id, "PUBLISHED");
    }

    @Transactional
    public void rejectQuestion(Long id) {
        changeStatus(id, "REJECTED");
    }

    /** Takes a published question out of the pool without deleting it. */
    @Transactional
    public void deactivateQuestion(Long id) {
        changeStatus(id, "RETIRED");
    }

    @Transactional
    public void activateQuestion(Long id) {
        if (!"RETIRED".equals(getQuestionById(id).getStatus())) {
            throw new BadRequestException("Only a deactivated question can be activated; publish it instead");
        }
        changeStatus(id, "PUBLISHED");
    }

    private void changeStatus(Long id, String target) {
        QuestionBankItemAdminDto existing = getQuestionById(id);
        if (target.equals(existing.getStatus())) {
            return;
        }
        requireTransition(existing.getStatus(), target);
        if ("PUBLISHED".equals(target)) {
            validateForPublish(existing);
        }
        jdbcTemplate.update("UPDATE question_bank_items SET status = ?, updated_at = NOW() WHERE id = ?", target, id);
        log.info("Admin moved question id={} from {} to {}", id, existing.getStatus(), target);
    }

    private static void requireTransition(String from, String to) {
        if (!TRANSITIONS.containsKey(to)) {
            throw new BadRequestException("Unknown status: " + to);
        }
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new BadRequestException("A " + from + " question cannot become " + to);
        }
    }

    private void validateForPublish(QuestionBankItemAdminDto item) {
        if (item.getLevelCode() == null || !LEVELS.contains(item.getLevelCode())) {
            throw new BadRequestException("A published question needs a level");
        }
        List<QuestionBankChoiceAdminDto> choices = item.getChoices() != null ? item.getChoices() : List.of();
        requireValidContent(item.getQuestionType(), item.getPrompt(), item.getAnswerText(),
                choices.stream().map(QuestionBankChoiceAdminDto::getText).toList(),
                (int) choices.stream().filter(QuestionBankChoiceAdminDto::isCorrect).count());
        ensureCalibration(item.getId(), item.getLevelCode(), item.getDifficulty());
    }

    private static void requireValidContent(String type, String prompt, String answerText,
                                            List<String> choiceTexts, int correctCount) {
        if (prompt == null || prompt.isBlank()) {
            throw new BadRequestException("Prompt cannot be blank");
        }
        if (answerText == null || answerText.isBlank()) {
            throw new BadRequestException(NO_CHOICE_TYPES.contains(type)
                    ? "A read-aloud question needs the text to read" : "Exactly one choice must be marked as correct");
        }
        if (NO_CHOICE_TYPES.contains(type)) {
            return;
        }
        if (choiceTexts.size() < 2) {
            throw new BadRequestException("A question needs at least two choices");
        }
        if (correctCount != 1) {
            throw new BadRequestException("Exactly one choice must be marked as correct");
        }
        Set<String> distinct = new HashSet<>();
        for (String text : choiceTexts) {
            if (text == null || text.isBlank()) {
                throw new BadRequestException("Choice text cannot be blank");
            }
            if (!distinct.add(text.trim())) {
                throw new BadRequestException("Choices must be different from each other");
            }
        }
    }

    /** Starting IRT estimate: the level (7-9 counts as 8) shifted a quarter step per difficulty point from 3. */
    private void ensureCalibration(Long questionId, String levelCode, Integer difficulty) {
        double level = "7-9".equals(levelCode) ? 8 : Double.parseDouble(levelCode);
        double b = level + ((difficulty != null ? difficulty : 3) - 3) * 0.25;
        jdbcTemplate.update(
                "INSERT INTO question_calibration (question_id, irt_b) VALUES (?, ?) ON CONFLICT (question_id) DO NOTHING",
                questionId, b);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public QuestionBankItemAdminDto updateQuestion(Long id, QuestionUpdateRequest request) {
        QuestionBankItemAdminDto existing = getQuestionById(id);

        // Find the correct answer text from choices
        String correctAnswerText = null;
        for (QuestionUpdateRequest.ChoiceUpdateRequest c : request.getChoices()) {
            if (c.isCorrect()) {
                correctAnswerText = c.getText();
                break;
            }
        }

        if (correctAnswerText == null || correctAnswerText.isBlank()) {
            throw new BadRequestException("At least one choice must be marked as correct");
        }

        String answerDataJson = "{}";
        try {
            answerDataJson = objectMapper.writeValueAsString(Map.of("correctAnswer", correctAnswerText));
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize answer_data", e);
        }

        String targetStatus = request.getStatus() != null && !request.getStatus().isBlank()
                ? request.getStatus().toUpperCase()
                : existing.getStatus();

        int diff = request.getDifficulty() != null ? Math.max(1, Math.min(request.getDifficulty(), 5)) : existing.getDifficulty();
        if (!targetStatus.equals(existing.getStatus())) {
            requireTransition(existing.getStatus(), targetStatus);
        }

        jdbcTemplate.update("""
                UPDATE question_bank_items
                SET prompt = ?,
                    prompt_pinyin = ?,
                    prompt_audio_url = ?,
                    explanation = ?,
                    answer_text = ?,
                    answer_data = ?::jsonb,
                    difficulty = ?,
                    status = ?,
                    updated_at = NOW()
                WHERE id = ?
                """,
                request.getPrompt(),
                request.getPromptPinyin(),
                request.getPromptAudioUrl() != null ? request.getPromptAudioUrl() : existing.getPromptAudioUrl(),
                request.getExplanation(),
                correctAnswerText,
                answerDataJson,
                diff,
                targetStatus,
                id);

        // Update choices: delete existing and reinsert
        jdbcTemplate.update("DELETE FROM question_bank_choices WHERE question_id = ?", id);

        int order = 1;
        for (QuestionUpdateRequest.ChoiceUpdateRequest c : request.getChoices()) {
            jdbcTemplate.update("""
                    INSERT INTO question_bank_choices(question_id, choice_key, choice_order, choice_text, is_correct)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    id, c.getKey(), order++, c.getText(), c.isCorrect());
        }

        QuestionBankItemAdminDto updated = getQuestionById(id);
        if ("PUBLISHED".equals(targetStatus)) {
            validateForPublish(updated);
        }
        log.info("Admin updated question id={}: prompt='{}', status='{}'", id, request.getPrompt(), targetStatus);
        return updated;
    }

    @Transactional
    public void deleteQuestion(Long id) {
        int deleted = jdbcTemplate.update("DELETE FROM question_bank_items WHERE id = ?", id);
        if (deleted == 0) {
            throw new ResourceNotFoundException("QuestionBankItem", "id", id);
        }
        log.info("Admin deleted question id={}", id);
    }

    // ── Internal Helpers ───────────────────────────────────────────────────

    private QuestionBankItemAdminDto mapItemRow(ResultSet rs, int rowNum) throws SQLException {
        return QuestionBankItemAdminDto.builder()
                .id(rs.getLong("id"))
                .questionCode(rs.getString("question_code"))
                .questionType(rs.getString("question_type"))
                .skillKey(rs.getString("skill_key"))
                .standardCode(rs.getString("standard_code"))
                .versionCode(rs.getString("version_code"))
                .levelCode(rs.getString("level_code"))
                .topicKey(rs.getString("topic_key"))
                .prompt(rs.getString("prompt"))
                .promptPinyin(rs.getString("prompt_pinyin"))
                .promptAudioUrl(rs.getString("prompt_audio_url"))
                .answerText(rs.getString("answer_text"))
                .explanation(rs.getString("explanation"))
                .difficulty(rs.getInt("difficulty"))
                .origin(rs.getString("origin"))
                .status(rs.getString("status"))
                .lessonId(rs.getObject("lesson_id") != null ? rs.getLong("lesson_id") : null)
                .contentHash(rs.getString("content_hash"))
                .sourceRawRecordId(rs.getObject("source_raw_record_id") != null ? rs.getLong("source_raw_record_id") : null)
                .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null)
                .updatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null)
                .build();
    }

    private Map<Long, List<QuestionBankChoiceAdminDto>> loadChoicesForQuestions(List<Long> questionIds) {
        if (questionIds.isEmpty()) return Collections.emptyMap();

        String inSql = String.join(",", Collections.nCopies(questionIds.size(), "?"));
        String sql = """
                SELECT id, question_id, choice_key, choice_order, choice_text, choice_audio_url, is_correct
                FROM question_bank_choices
                WHERE question_id IN (%s)
                ORDER BY question_id, choice_order
                """.formatted(inSql);

        Map<Long, List<QuestionBankChoiceAdminDto>> map = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            Long qId = rs.getLong("question_id");
            QuestionBankChoiceAdminDto choice = QuestionBankChoiceAdminDto.builder()
                    .id(rs.getLong("id"))
                    .key(rs.getString("choice_key"))
                    .order(rs.getInt("choice_order"))
                    .text(rs.getString("choice_text"))
                    .audioUrl(rs.getString("choice_audio_url"))
                    .correct(rs.getBoolean("is_correct"))
                    .build();
            map.computeIfAbsent(qId, k -> new ArrayList<>()).add(choice);
        }, questionIds.toArray());

        return map;
    }
}

