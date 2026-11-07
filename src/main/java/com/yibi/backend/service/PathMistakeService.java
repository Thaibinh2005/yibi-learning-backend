package com.yibi.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.service.plan.PathQuiz;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Remembers the questions a learner got wrong on the path. A mistake stays open until the question is answered
 * correctly twice in a row, in any quiz or in the mistakes review; a wrong answer starts the count again.
 */
@Service
@RequiredArgsConstructor
public class PathMistakeService {

    public static final int STREAK_TO_RESOLVE = 2;
    private static final String ID_PREFIX = "m";
    private static final Pattern QUESTION_ID = Pattern.compile("m\\d{1,18}");
    private static final int LIST_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** A question to ask again; its id is unique across quizzes, unlike the q0, q1... of a generated quiz. */
    public record Mistake(long id, String questionId, int level, String skill, String type, String prompt, String pinyin,
                          String audioText, List<String> options, String answer, String explanation, String lastPicked,
                          int wrongCount, int correctStreak, OffsetDateTime lastWrongAt) { }

    public record Summary(int open, int resolved, int streakToResolve, List<Mistake> items) { }

    public static boolean isMistakeId(String questionId) {
        return questionId != null && QUESTION_ID.matcher(questionId).matches();
    }

    /** Stays the same however often the question is generated, since its prompt and answer do. */
    public static String keyOf(String prompt, String answer) {
        return UUID.nameUUIDFromBytes((prompt + '\n' + answer).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Saves a question answered wrongly, or reopens it if it was seen before. */
    public void recordWrong(UUID userId, long nodeId, int level, PathQuiz.Question question, String picked) {
        jdbc.update("""
                INSERT INTO path_mistakes (user_id, node_id, hsk_level, skill_key, question_key, question_type, prompt,
                                           pinyin, audio_text, options, answer, explanation, last_picked)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (user_id, question_key) DO UPDATE SET
                  wrong_count = path_mistakes.wrong_count + 1, correct_streak = 0, last_picked = EXCLUDED.last_picked,
                  options = EXCLUDED.options, last_wrong_at = NOW(), last_seen_at = NOW(), resolved_at = NULL
                """, userId, nodeId, level, question.skill(), keyOf(question.prompt(), question.answer()), question.type(),
                question.prompt(), question.pinyin(), question.audioText(), toJson(question.options()), question.answer(),
                question.explanation(), picked);
    }

    /** A right answer to a question that also has an open mistake counts towards resolving it. */
    public boolean recordRight(UUID userId, PathQuiz.Question question) {
        return resolved(jdbc.query("""
                UPDATE path_mistakes SET correct_streak = correct_streak + 1, last_seen_at = NOW(),
                  resolved_at = CASE WHEN correct_streak + 1 >= ? THEN NOW() END
                WHERE user_id = ? AND question_key = ? AND resolved_at IS NULL RETURNING resolved_at IS NOT NULL
                """, (rs, i) -> rs.getBoolean(1), STREAK_TO_RESOLVE, userId, keyOf(question.prompt(), question.answer())));
    }

    /** Oldest-seen first, without the questions this quiz already asks. */
    public List<Mistake> dueForQuiz(UUID userId, int upToLevel, int limit, Collection<String> keysInQuiz) {
        String skip = keysInQuiz.isEmpty() ? "" : " AND question_key NOT IN (" + String.join(",", Collections.nCopies(keysInQuiz.size(), "?")) + ")";
        List<Object> args = new ArrayList<>(List.of(userId, upToLevel));
        args.addAll(keysInQuiz);
        args.add(limit);
        return jdbc.query("SELECT * FROM path_mistakes WHERE user_id = ? AND resolved_at IS NULL AND hsk_level <= ?"
                + skip + " ORDER BY last_seen_at, id LIMIT ?", this::toMistake, args.toArray());
    }

    public Mistake load(UUID userId, String questionId) {
        if (!isMistakeId(questionId)) throw new BadRequestException("Câu hỏi không thuộc bài này.");
        return jdbc.query("SELECT * FROM path_mistakes WHERE id = ? AND user_id = ?", this::toMistake,
                Long.parseLong(questionId.substring(ID_PREFIX.length())), userId).stream().findFirst()
                .orElseThrow(() -> new BadRequestException("Câu hỏi không thuộc bài này."));
    }

    /** Grades a question asked again; returns whether that resolved it. An already resolved question changes nothing. */
    public boolean answerAgain(UUID userId, Mistake mistake, String picked) {
        if (mistake.answer().equals(picked)) {
            return resolved(jdbc.query("""
                    UPDATE path_mistakes SET correct_streak = correct_streak + 1, last_seen_at = NOW(),
                      resolved_at = CASE WHEN correct_streak + 1 >= ? THEN NOW() END
                    WHERE id = ? AND user_id = ? AND resolved_at IS NULL RETURNING resolved_at IS NOT NULL
                    """, (rs, i) -> rs.getBoolean(1), STREAK_TO_RESOLVE, mistake.id(), userId));
        }
        jdbc.update("""
                UPDATE path_mistakes SET wrong_count = wrong_count + 1, correct_streak = 0, last_picked = ?,
                  last_wrong_at = NOW(), last_seen_at = NOW()
                WHERE id = ? AND user_id = ? AND resolved_at IS NULL
                """, picked, mistake.id(), userId);
        return false;
    }

    public int countOpen(UUID userId) {
        Integer open = jdbc.queryForObject("SELECT count(*) FROM path_mistakes WHERE user_id = ? AND resolved_at IS NULL", Integer.class, userId);
        return open == null ? 0 : open;
    }

    public Summary summary(UUID userId) {
        Integer resolved = jdbc.queryForObject("SELECT count(*) FROM path_mistakes WHERE user_id = ? AND resolved_at IS NOT NULL", Integer.class, userId);
        List<Mistake> items = jdbc.query("""
                SELECT * FROM path_mistakes WHERE user_id = ? AND resolved_at IS NULL
                ORDER BY wrong_count DESC, last_wrong_at DESC LIMIT ?
                """, this::toMistake, userId, LIST_LIMIT);
        return new Summary(countOpen(userId), resolved == null ? 0 : resolved, STREAK_TO_RESOLVE, items);
    }

    private static boolean resolved(List<Boolean> updated) {
        return !updated.isEmpty() && updated.get(0);
    }

    private Mistake toMistake(ResultSet rs, int row) throws SQLException {
        long id = rs.getLong("id");
        return new Mistake(id, ID_PREFIX + id, rs.getInt("hsk_level"), rs.getString("skill_key"), rs.getString("question_type"),
                rs.getString("prompt"), rs.getString("pinyin"), rs.getString("audio_text"), parseOptions(rs.getString("options")),
                rs.getString("answer"), rs.getString("explanation"), rs.getString("last_picked"), rs.getInt("wrong_count"),
                rs.getInt("correct_streak"), rs.getObject("last_wrong_at", OffsetDateTime.class));
    }

    private List<String> parseOptions(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new IllegalStateException("Bad mistake options", ex);
        }
    }

    private String toJson(List<String> options) {
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Bad mistake options", ex);
        }
    }
}
