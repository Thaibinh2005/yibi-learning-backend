package com.yibi.backend.service;

import com.yibi.backend.dto.placement.PlacementHistoryItem;
import com.yibi.backend.dto.placement.PlacementReviewResponse;
import com.yibi.backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Looking back at placement tests: the list of finished attempts, and every question of one attempt with the right answer.
 * Answers are only shown for attempts that have a stored result, which exists only once the test is finished,
 * so the review can never be used to see the answers of a test still in progress.
 */
@Service
@RequiredArgsConstructor
public class PlacementReviewService {

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<PlacementHistoryItem> history(UUID userId) {
        return jdbc.query("""
                SELECT r.assessment_id, r.estimated_level, r.confidence, r.question_count, a.started_at,
                       COALESCE(a.finished_at, r.created_at) AS finished_at,
                       (SELECT COUNT(*) FROM skill_attempts s WHERE s.assessment_id = r.assessment_id AND s.is_correct) AS correct_count,
                       (SELECT COALESCE(SUM(s.response_time_seconds), 0) FROM skill_attempts s WHERE s.assessment_id = r.assessment_id) AS duration
                FROM placement_results r
                JOIN placement_assessments a ON a.id = r.assessment_id
                WHERE r.user_id = ?
                ORDER BY finished_at DESC
                """, (rs, i) -> PlacementHistoryItem.builder()
                        .attemptId(rs.getLong("assessment_id"))
                        .level(rs.getInt("estimated_level"))
                        .confidence(rs.getString("confidence"))
                        .questionCount(rs.getInt("question_count"))
                        .correctCount(rs.getInt("correct_count"))
                        .startedAt(toInstant(rs.getTimestamp("started_at")))
                        .finishedAt(toInstant(rs.getTimestamp("finished_at")))
                        .durationSeconds(rs.getInt("duration"))
                        .build(), userId);
    }

    @Transactional(readOnly = true)
    public PlacementReviewResponse review(UUID userId, Long attemptId) {
        List<Map<String, Object>> header = jdbc.queryForList("""
                SELECT r.estimated_level, COALESCE(a.finished_at, r.created_at) AS finished_at
                FROM placement_results r JOIN placement_assessments a ON a.id = r.assessment_id
                WHERE r.assessment_id = ? AND r.user_id = ?
                """, attemptId, userId);
        if (header.isEmpty()) {
            throw new ResourceNotFoundException("Placement result", "attempt", attemptId);
        }

        Map<Long, PlacementReviewResponse.Item> items = new LinkedHashMap<>();
        Map<Long, Long> questionOf = new LinkedHashMap<>();
        jdbc.query("""
                SELECT s.id, s.display_order, s.skill_key, s.is_correct, s.response_time_seconds, s.question_id,
                       q.question_type, q.level_code, q.prompt, q.prompt_pinyin, q.prompt_audio_url, q.answer_text, q.explanation,
                       c.choice_key AS selected_key
                FROM skill_attempts s
                LEFT JOIN question_bank_items q ON q.id = s.question_id
                LEFT JOIN question_bank_choices c ON c.id = s.selected_choice_id
                WHERE s.assessment_id = ?
                ORDER BY s.display_order NULLS LAST, s.id
                """, rs -> {
            long attemptRow = rs.getLong("id");
            Long questionId = rs.getObject("question_id") == null ? null : rs.getLong("question_id");
            String skill = rs.getString("skill_key");
            items.put(attemptRow, PlacementReviewResponse.Item.builder()
                    .order(items.size() + 1)
                    .questionId(questionId)
                    .skill(skill)
                    .skillLabel(PlacementService.SKILL_LABELS.getOrDefault(skill, skill))
                    .type(rs.getString("question_type"))
                    .levelCode(rs.getString("level_code"))
                    .prompt(rs.getString("prompt"))
                    .promptPinyin(rs.getString("prompt_pinyin"))
                    .audioUrl(rs.getString("prompt_audio_url"))
                    .answerText(rs.getString("answer_text"))
                    .explanation(rs.getString("explanation"))
                    .selectedChoiceKey(rs.getString("selected_key"))
                    .correct(rs.getBoolean("is_correct"))
                    .responseTimeSeconds(rs.getInt("response_time_seconds"))
                    .choices(new ArrayList<>())
                    .build());
            if (questionId != null) questionOf.put(attemptRow, questionId);
        }, attemptId);

        if (!questionOf.isEmpty()) {
            Map<Long, List<PlacementReviewResponse.Choice>> choicesByQuestion = new LinkedHashMap<>();
            String ids = String.join(",", questionOf.values().stream().distinct().map(String::valueOf).toList());
            jdbc.query("SELECT question_id, choice_key, choice_text, is_correct FROM question_bank_choices WHERE question_id IN ("
                    + ids + ") ORDER BY question_id, choice_order", rs -> {
                choicesByQuestion.computeIfAbsent(rs.getLong("question_id"), k -> new ArrayList<>())
                        .add(new PlacementReviewResponse.Choice(rs.getString("choice_key"), rs.getString("choice_text"), rs.getBoolean("is_correct")));
            });
            questionOf.forEach((row, questionId) -> items.get(row).setChoices(choicesByQuestion.getOrDefault(questionId, List.of())));
        }

        return PlacementReviewResponse.builder()
                .attemptId(attemptId)
                .level(((Number) header.get(0).get("estimated_level")).intValue())
                .finishedAt(toInstant((Timestamp) header.get(0).get("finished_at")))
                .items(new ArrayList<>(items.values()))
                .build();
    }

    /** The placement columns are TIMESTAMP without zone, written in UTC (hibernate.jdbc.time_zone), so read them as UTC. */
    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}
