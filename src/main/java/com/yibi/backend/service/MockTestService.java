package com.yibi.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.service.mocktest.MockTestCatalog;
import com.yibi.backend.service.mocktest.MockTestPapers;
import com.yibi.backend.service.mocktest.MockTestPapers.Item;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Learners' attempts at the published HSK 3.0 sample papers (see {@link MockTestCatalog} for where papers live).
 *
 * <p>An attempt is PRACTICE (the page shows each answer as soon as it is chosen, so its paper carries the answers)
 * or EXAM (timed; the paper is sent without answers or transcripts until it is handed in). An attempt keeps the
 * paper version it started on. Each skill is scored out of 100 like the real test (HSK 1: listening + reading = 200)
 * and 60% of the total passes. Open writing ("write" questions) is kept for the learner to compare with the model
 * answer but not scored; a skill with only open writing gets no score.
 */
@Service
public class MockTestService {

    static final double PASS_SHARE = 0.6;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MockTestCatalog catalog;

    public MockTestService(JdbcTemplate jdbc, ObjectMapper json, MockTestCatalog catalog) {
        this.jdbc = jdbc;
        this.json = json;
        this.catalog = catalog;
    }

    // ── Answers and views ─────────────────────────────────────────────────────

    public record Part(String skill, int questionCount, Integer minutes) {}

    public record OpenAttempt(long id, String mode, int answeredCount, int currentIndex) {}

    public record FinishedAttempt(long id, String mode, int score, int maxScore, boolean passed, int correct, int total,
                                  OffsetDateTime finishedAt) {}

    public record Summary(String id, String title, int level, String stage, String source, int durationMinutes,
                          int questionCount, List<Part> parts, OpenAttempt openAttempt, FinishedAttempt lastResult) {}

    public record SkillScore(int correct, int total, int score) {}

    public record Attempt(long id, String testId, int paperVersion, String mode, Map<String, String> answers, int currentIndex,
                          int elapsedSeconds, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                          Integer correct, Integer total, Integer score, Integer maxScore, Boolean passed,
                          Map<String, SkillScore> skills) {}

    /** The attempt and the paper as this attempt may see it (without answers during a timed exam). */
    public record AttemptView(Attempt attempt, JsonNode paper) {}

    // ── Papers ────────────────────────────────────────────────────────────────

    private JsonNode paperOf(Attempt attempt) {
        return catalog.paper(attempt.testId(), attempt.paperVersion());
    }

    // ── Listing ───────────────────────────────────────────────────────────────

    public List<Summary> list(UUID userId) {
        List<Summary> out = new ArrayList<>();
        for (MockTestCatalog.Published published : catalog.published()) {
            JsonNode p = published.paper();
            String id = published.id();
            List<Part> parts = new ArrayList<>();
            for (JsonNode part : p.path("parts")) {
                parts.add(new Part(part.path("skill").asText(), part.path("questionCount").asInt(),
                        part.hasNonNull("minutes") ? part.path("minutes").asInt() : null));
            }
            out.add(new Summary(id, p.path("title").asText(), p.path("level").asInt(), p.path("stage").asText(),
                    p.path("source").asText(), p.path("durationMinutes").asInt(), p.path("questionCount").asInt(), parts,
                    userId == null ? null : openAttempt(userId, id), userId == null ? null : lastResult(userId, id)));
        }
        return out;
    }

    private OpenAttempt openAttempt(UUID userId, String testId) {
        return jdbc.query("""
                SELECT id, mode, current_index, (SELECT count(*) FROM jsonb_object_keys(answers)) AS answered
                FROM mock_test_attempts WHERE user_id = ? AND test_id = ? AND finished_at IS NULL
                """, rs -> rs.next() ? new OpenAttempt(rs.getLong("id"), rs.getString("mode"), rs.getInt("answered"),
                rs.getInt("current_index")) : null, userId, testId);
    }

    private FinishedAttempt lastResult(UUID userId, String testId) {
        return jdbc.query("""
                SELECT id, mode, score, max_score, correct_count, question_count, finished_at
                FROM mock_test_attempts WHERE user_id = ? AND test_id = ? AND finished_at IS NOT NULL
                ORDER BY finished_at DESC LIMIT 1
                """, rs -> rs.next() ? new FinishedAttempt(rs.getLong("id"), rs.getString("mode"), rs.getInt("score"),
                rs.getInt("max_score"), rs.getInt("score") >= Math.ceil(rs.getInt("max_score") * PASS_SHARE),
                rs.getInt("correct_count"), rs.getInt("question_count"),
                rs.getObject("finished_at", OffsetDateTime.class)) : null, userId, testId);
    }

    // ── Attempts ──────────────────────────────────────────────────────────────

    /** Resumes the learner's unfinished attempt at this paper, or starts one in the chosen mode. */
    @Transactional
    public AttemptView start(UUID userId, String testId, String mode) {
        int version = catalog.publishedVersion(testId);
        if (!"PRACTICE".equals(mode) && !"EXAM".equals(mode)) throw new BadRequestException("Chế độ làm bài không hợp lệ.");
        OpenAttempt open = openAttempt(userId, testId);
        if (open != null) return view(userId, open.id());
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO mock_test_attempts (user_id, test_id, mode, paper_version) VALUES (?, ?, ?, ?) RETURNING id
                    """, Long.class, userId, testId, mode, version);
            return view(userId, id);
        } catch (DuplicateKeyException race) {
            return view(userId, openAttempt(userId, testId).id());
        }
    }

    public AttemptView view(UUID userId, long attemptId) {
        Attempt attempt = attempt(userId, attemptId);
        return new AttemptView(attempt, MockTestPapers.visiblePaper(paperOf(attempt), attempt.mode(), attempt.finishedAt() != null));
    }

    /** Saves answers and position while the attempt is open; only possible answers to questions of the paper are kept. */
    @Transactional
    public Attempt save(UUID userId, long attemptId, Map<String, String> answers, int currentIndex, int elapsedSeconds) {
        Attempt attempt = attempt(userId, attemptId);
        if (attempt.finishedAt() != null) throw new BadRequestException("Bài này đã nộp.");
        Map<Integer, Item> key = MockTestPapers.answerKey(paperOf(attempt));
        Map<String, String> kept = new LinkedHashMap<>();
        if (answers != null) {
            answers.forEach((no, value) -> {
                Item item = no.matches("\\d{1,3}") ? key.get(Integer.parseInt(no)) : null;
                String answer = item == null ? null : MockTestPapers.keepAnswer(item, value);
                if (answer != null) kept.put(no, answer);
            });
        }
        int index = Math.max(0, Math.min(currentIndex, key.size() - 1));
        jdbc.update("""
                UPDATE mock_test_attempts SET answers = ?::jsonb, current_index = ?, elapsed_seconds = GREATEST(elapsed_seconds, ?),
                       updated_at = now() WHERE id = ?
                """, write(kept), index, Math.max(0, elapsedSeconds), attemptId);
        return attempt(userId, attemptId);
    }

    /** Hands the attempt in (with its last answers) and scores it. */
    @Transactional
    public AttemptView submit(UUID userId, long attemptId, Map<String, String> answers, int currentIndex, int elapsedSeconds) {
        Attempt attempt = save(userId, attemptId, answers, currentIndex, elapsedSeconds);
        Map<Integer, Item> key = MockTestPapers.answerKey(paperOf(attempt));
        Map<String, int[]> perSkill = new LinkedHashMap<>();   // skill → {correct, total}
        int correct = 0;
        int graded = 0;
        for (Map.Entry<Integer, Item> e : key.entrySet()) {
            if (!e.getValue().graded()) continue;
            graded++;
            int[] tally = perSkill.computeIfAbsent(e.getValue().skill(), s -> new int[2]);
            tally[1]++;
            if (MockTestPapers.correct(e.getValue(), attempt.answers().get(String.valueOf(e.getKey())))) {
                tally[0]++;
                correct++;
            }
        }
        Map<String, SkillScore> skills = new LinkedHashMap<>();
        int score = 0;
        for (Map.Entry<String, int[]> e : perSkill.entrySet()) {
            int skillScore = (int) Math.round(100.0 * e.getValue()[0] / e.getValue()[1]);
            skills.put(e.getKey(), new SkillScore(e.getValue()[0], e.getValue()[1], skillScore));
            score += skillScore;
        }
        jdbc.update("""
                UPDATE mock_test_attempts SET finished_at = now(), correct_count = ?, question_count = ?, score = ?, max_score = ?,
                       skill_scores = ?::jsonb, updated_at = now() WHERE id = ? AND finished_at IS NULL
                """, correct, graded, score, 100 * skills.size(), write(skills), attemptId);
        return view(userId, attemptId);
    }

    /** Throws away an unfinished attempt ("làm lại từ đầu"). */
    @Transactional
    public void discard(UUID userId, long attemptId) {
        int removed = jdbc.update("DELETE FROM mock_test_attempts WHERE id = ? AND user_id = ? AND finished_at IS NULL", attemptId, userId);
        if (removed == 0) throw new ResourceNotFoundException("Không tìm thấy bài đang làm.");
    }

    private Attempt attempt(UUID userId, long attemptId) {
        List<Attempt> found = jdbc.query("""
                SELECT id, test_id, paper_version, mode, answers::text AS answers, current_index, elapsed_seconds, started_at, finished_at,
                       correct_count, question_count, score, max_score, skill_scores::text AS skills
                FROM mock_test_attempts WHERE id = ? AND user_id = ?
                """, (rs, i) -> {
            Integer score = (Integer) rs.getObject("score");
            Integer max = (Integer) rs.getObject("max_score");
            return new Attempt(rs.getLong("id"), rs.getString("test_id"), rs.getInt("paper_version"), rs.getString("mode"),
                    read(rs.getString("answers"), new TypeReference<Map<String, String>>() {}),
                    rs.getInt("current_index"), rs.getInt("elapsed_seconds"),
                    rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class),
                    (Integer) rs.getObject("correct_count"), (Integer) rs.getObject("question_count"), score, max,
                    score == null || max == null ? null : score >= Math.ceil(max * PASS_SHARE),
                    rs.getString("skills") == null ? null : read(rs.getString("skills"), new TypeReference<Map<String, SkillScore>>() {}));
        }, attemptId, userId);
        if (found.isEmpty()) throw new ResourceNotFoundException("Không tìm thấy bài làm.");
        return found.get(0);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
