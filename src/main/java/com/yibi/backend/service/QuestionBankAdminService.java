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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionBankAdminService {

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

    @Transactional
    public void publishQuestion(Long id) {
        int updated = jdbcTemplate.update("""
                UPDATE question_bank_items
                SET status = 'PUBLISHED', updated_at = NOW()
                WHERE id = ?
                """, id);
        if (updated == 0) {
            throw new ResourceNotFoundException("QuestionBankItem", "id", id);
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

        log.info("Admin updated question id={}: prompt='{}', status='{}'", id, request.getPrompt(), targetStatus);
        return getQuestionById(id);
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

