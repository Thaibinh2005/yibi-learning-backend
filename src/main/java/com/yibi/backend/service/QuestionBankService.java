package com.yibi.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.dto.practice.MasterySummaryResponse;
import com.yibi.backend.dto.practice.SkillMasteryStat;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Persists validated lesson exercises and records server-graded practice attempts. */
@Service
@RequiredArgsConstructor
public class QuestionBankService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public void storeGeneratedActivities(Long lessonId, String sourceHash, Map<String, Object> generated) {
        List<?> activities = asList(generated.get("activities"));
        int order = 0;
        for (Object raw : activities) {
            if (!(raw instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> activity = (Map<String, Object>) raw;
            String skill = string(activity.get("skill"));
            String prompt = string(activity.get("question"));
            String answer = string(activity.get("answer"));
            List<?> options = asList(activity.get("options"));
            if (prompt.isBlank() || answer.isBlank() || options.size() < 2) continue;

            String canonical;
            try {
                canonical = objectMapper.writeValueAsString(Map.of(
                        "lesson", lessonId,
                        "sourceHash", sourceHash,
                        "skill", skill,
                        "topic", string(activity.get("topic_key")),
                        "question", prompt,
                        "options", options,
                        "answer", answer,
                        "explanation", string(activity.get("explanation")),
                        "evidence", string(activity.get("evidence")),
                        "difficulty", string(activity.get("difficulty"))));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Could not serialize generated question", ex);
            }
            String hash = sha256(canonical);
            String code = "hsk-" + lessonId + "-" + hash.substring(0, 24);
            String levelCode = jdbcTemplate.queryForObject(
                    "SELECT c.hsk_level::text FROM lessons l JOIN courses c ON c.id=l.course_id WHERE l.id=?",
                    String.class, lessonId);
            int difficulty = "medium".equalsIgnoreCase(string(activity.get("difficulty"))) ? 2 : 1;
            String questionType = questionType(skill);
            Long questionId = jdbcTemplate.queryForObject("""
                    INSERT INTO question_bank_items
                      (question_code, question_type, skill_key, standard_code, version_code,
                       level_code, topic_key, prompt, answer_text, explanation, difficulty,
                       origin, status, lesson_id, content_hash)
                    VALUES (?, ?, ?, 'NEW_HSK', '2025', ?, ?, ?, ?, ?, ?, 'AI_GENERATED', 'PUBLISHED', ?, ?)
                    ON CONFLICT (content_hash) WHERE content_hash IS NOT NULL
                    DO UPDATE SET updated_at=NOW()
                    RETURNING id
                    """, Long.class, code, questionType, skill, levelCode,
                    topicFor(skill, activity.get("topic_key")), prompt, answer, string(activity.get("explanation")), difficulty,
                    lessonId, hash);

            int choiceOrder = 0;
            for (Object rawOption : options) {
                String option = string(rawOption);
                if (option.isBlank()) continue;
                String choiceKey = String.valueOf((char) ('A' + choiceOrder));
                jdbcTemplate.update("""
                        INSERT INTO question_bank_choices(question_id, choice_key, choice_order, choice_text, is_correct)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (question_id, choice_key) DO UPDATE SET
                          choice_order=EXCLUDED.choice_order, choice_text=EXCLUDED.choice_text,
                          is_correct=EXCLUDED.is_correct
                        """, questionId, choiceKey, ++choiceOrder, option,
                        normalise(option).equals(normalise(answer)));
            }

            for (Object rawRef : asList(activity.get("source_refs"))) {
                if (!(rawRef instanceof Map<?, ?> ref)) continue;
                Object section = ref.get("section");
                Object index = ref.get("item_index");
                if (section == null || !(index instanceof Number number)) continue;
                jdbcTemplate.update("""
                        INSERT INTO question_bank_sources
                          (question_id, source_section, source_item_index, evidence_text, attribution_note)
                        VALUES (?, ?, ?, ?, 'Generated from validated lesson source evidence')
                        ON CONFLICT (question_id, source_section, source_item_index)
                          WHERE raw_record_id IS NULL DO UPDATE SET evidence_text=EXCLUDED.evidence_text
                        """, questionId, string(section), number.intValue(), string(activity.get("evidence")));
            }
            activity.put("question_code", code);
            order++;
        }
        generated.put("question_bank_saved", true);
        generated.put("question_count", order);
    }

    @Transactional
    public Long startLessonSession(Long lessonId, java.util.UUID userId) {
        Integer level = jdbcTemplate.query("""
                SELECT c.hsk_level FROM lessons l JOIN courses c ON c.id=l.course_id WHERE l.id=?
                """, rs -> rs.next() ? rs.getInt(1) : null, lessonId);
        if (level == null) throw new ResourceNotFoundException("Lesson", "id", lessonId);
        Long sessionId = jdbcTemplate.queryForObject("""
                INSERT INTO practice_sessions(user_id, mode, standard_code, version_code, level_code, lesson_id)
                VALUES (?, 'LESSON', 'NEW_HSK', '2025', ?, ?) RETURNING id
                """, Long.class, userId, String.valueOf(level), lessonId);
        jdbcTemplate.update("""
                INSERT INTO practice_session_questions(session_id, question_id, display_order, selection_reason)
                SELECT ?, id, row_number() OVER (ORDER BY id), 'LESSON_CONTENT'
                FROM question_bank_items WHERE lesson_id=? AND status='PUBLISHED' AND question_type <> 'READ_ALOUD'
                ON CONFLICT (session_id, question_id) DO NOTHING
                """, sessionId, lessonId);
        return sessionId;
    }

    @Transactional
    public Map<String, Object> startAdaptiveSession(java.util.UUID userId, String levelCode, int requestedLimit) {
        int limit = Math.max(5, Math.min(requestedLimit, 30));
        Long sessionId = jdbcTemplate.queryForObject("""
                INSERT INTO practice_sessions(user_id, mode, standard_code, version_code, level_code)
                VALUES (?, 'ADAPTIVE', 'NEW_HSK', '2025', ?) RETURNING id
                """, Long.class, userId, levelCode);

        List<Map<String, Object>> selected = jdbcTemplate.query("""
                WITH candidates AS (
                  SELECT q.id, q.question_code, q.question_type, q.skill_key, q.topic_key,
                         q.prompt, q.prompt_pinyin, q.prompt_audio_url, q.difficulty,
                         m.mastery_score, m.next_review_at,
                         (SELECT max(a.attempted_at) FROM question_attempts a
                           WHERE a.user_id=? AND a.question_id=q.id) AS last_seen,
                         CASE
                           WHEN m.user_id IS NOT NULL AND m.next_review_at <= NOW() THEN 0
                           WHEN m.user_id IS NOT NULL AND m.mastery_score < 0.70 THEN 1
                           ELSE 2
                         END AS priority
                  FROM question_bank_items q
                  LEFT JOIN learner_skill_mastery m
                    ON m.user_id=? AND m.standard_code=q.standard_code AND m.version_code=q.version_code
                   AND m.level_code=q.level_code AND m.skill_key=q.skill_key AND m.topic_key=q.topic_key
                  WHERE q.status='PUBLISHED' AND q.standard_code='NEW_HSK' AND q.version_code='2025'
                    AND q.level_code=? AND q.question_type <> 'READ_ALOUD'
                ), ranked AS (
                  SELECT candidates.*,
                         row_number() OVER (PARTITION BY skill_key ORDER BY priority,
                           mastery_score ASC NULLS FIRST, last_seen ASC NULLS FIRST, random()) AS skill_order
                  FROM candidates
                )
                SELECT * FROM ranked ORDER BY skill_order, priority, mastery_score ASC NULLS FIRST, random()
                LIMIT ?
                """, (rs, rowNum) -> {
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("questionCode", rs.getString("question_code"));
                    item.put("questionType", rs.getString("question_type"));
                    item.put("skillKey", rs.getString("skill_key"));
                    item.put("topicKey", rs.getString("topic_key"));
                    item.put("prompt", rs.getString("prompt"));
                    item.put("promptPinyin", rs.getString("prompt_pinyin"));
                    item.put("promptAudioUrl", rs.getString("prompt_audio_url"));
                    item.put("selectionReason", selectionReason(rs.getInt("priority")));
                    return item;
                }, userId, userId, levelCode, limit);
        if (selected.isEmpty()) throw new BadRequestException("Ngân hàng chưa có câu hỏi đã phát hành ở HSK " + levelCode + ".");

        int order = 1;
        for (Map<String, Object> item : selected) {
            Long questionId = (Long) item.remove("id");
            item.put("options", jdbcTemplate.query("""
                    SELECT choice_key, choice_text FROM question_bank_choices
                    WHERE question_id=? ORDER BY choice_order
                    """, (rs, rowNum) -> Map.of("key", rs.getString(1), "text", rs.getString(2)), questionId));
            item.put("questionId", questionId);
            jdbcTemplate.update("""
                    INSERT INTO practice_session_questions(session_id,question_id,display_order,selection_reason)
                    VALUES (?,?,?,?)
                    """, sessionId, questionId, order++, item.get("selectionReason"));
        }
        return Map.of("sessionId", sessionId, "levelCode", levelCode, "questions", selected);
    }

    @Transactional
    public Map<String, Object> submitAnswer(Long sessionId, java.util.UUID userId, String questionCode,
                                           String submittedAnswer, Integer responseTimeMs) {
        Map<String, Object> session = jdbcTemplate.query("""
                SELECT id, level_code, session_state, lesson_id FROM practice_sessions WHERE id=? AND user_id=?
                """, rs -> rs.next() ? Map.of(
                        "id", rs.getLong("id"),
                        "level", rs.getString("level_code"),
                        "lessonId", rs.getObject("lesson_id") == null ? 0L : rs.getLong("lesson_id"),
                        "state", rs.getString("session_state")) : null, sessionId, userId);
        if (session == null) throw new ResourceNotFoundException("Practice session", "id", sessionId);
        if (!"IN_PROGRESS".equals(session.get("state"))) throw new BadRequestException("Lượt luyện tập này đã kết thúc.");

        Map<String, Object> question = jdbcTemplate.query("""
                SELECT id, skill_key, topic_key, level_code, answer_text, explanation, lesson_id
                FROM question_bank_items WHERE question_code=? AND status='PUBLISHED'
                """, rs -> rs.next() ? Map.of(
                        "id", rs.getLong("id"), "skill", rs.getString("skill_key"),
                        "topic", rs.getString("topic_key"), "level", rs.getString("level_code"),
                        "answer", rs.getString("answer_text") == null ? "" : rs.getString("answer_text"),
                        "explanation", rs.getString("explanation") == null ? "" : rs.getString("explanation"),
                        "lessonId", rs.getObject("lesson_id") == null ? 0L : rs.getLong("lesson_id")) : null,
                questionCode);
        if (question == null) throw new ResourceNotFoundException("Published question", "code", questionCode);
        if (!String.valueOf(session.get("level")).equals(String.valueOf(question.get("level")))) {
            throw new BadRequestException("Câu hỏi không thuộc cấp độ của lượt luyện tập này.");
        }
        long sessionLessonId = ((Number) session.get("lessonId")).longValue();
        long questionLessonId = ((Number) question.get("lessonId")).longValue();
        if (sessionLessonId > 0 && sessionLessonId != questionLessonId) {
            throw new BadRequestException("Câu hỏi không thuộc bài học của lượt luyện tập này.");
        }
        Long questionId = (Long) question.get("id");
        Boolean assigned = jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM practice_session_questions WHERE session_id=? AND question_id=?)
                """, Boolean.class, sessionId, questionId);
        if (!Boolean.TRUE.equals(assigned)) throw new BadRequestException("Câu hỏi chưa được gán cho lượt luyện tập này.");

        Boolean correct = jdbcTemplate.query("""
                SELECT is_correct FROM question_bank_choices
                WHERE question_id=? AND lower(btrim(choice_text))=lower(btrim(?))
                """, rs -> rs.next() ? rs.getBoolean(1) : null, questionId, submittedAnswer);
        if (correct == null) throw new BadRequestException("Đáp án không thuộc danh sách lựa chọn của câu hỏi.");
        Long choiceId = jdbcTemplate.query("""
                SELECT id FROM question_bank_choices
                WHERE question_id=? AND lower(btrim(choice_text))=lower(btrim(?))
                """, rs -> rs.next() ? rs.getLong(1) : null, questionId, submittedAnswer);

        jdbcTemplate.update("""
                INSERT INTO question_attempts(session_id,user_id,question_id,selected_choice_id,
                  submitted_answer,is_correct,response_time_ms)
                VALUES (?,?,?, ?, jsonb_build_object('text',?), ?, ?)
                """, sessionId, userId, questionId, choiceId, submittedAnswer, correct,
                Math.max(0, responseTimeMs == null ? 0 : responseTimeMs));

        Map<String, Object> masteryResult = updateMastery(userId, string(question.get("level")), string(question.get("skill")),
                string(question.get("topic")), correct);

        Integer unansweredCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM practice_session_questions pq
                LEFT JOIN question_attempts qa ON qa.session_id=pq.session_id AND qa.question_id=pq.question_id
                WHERE pq.session_id=? AND qa.id IS NULL
                """, Integer.class, sessionId);
        boolean sessionCompleted = unansweredCount != null && unansweredCount == 0;
        if (sessionCompleted) {
            jdbcTemplate.update("""
                    UPDATE practice_sessions SET session_state='COMPLETED', finished_at=NOW()
                    WHERE id=? AND session_state='IN_PROGRESS'
                    """, sessionId);
        }

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("correct", correct);
        response.put("correctAnswer", string(question.get("answer")));
        response.put("explanation", string(question.get("explanation")));
        response.put("streak", masteryResult.get("streak"));
        response.put("masteryScore", masteryResult.get("masteryScore"));
        response.put("intervalDays", masteryResult.get("intervalDays"));
        response.put("nextReviewAt", masteryResult.get("nextReviewAt"));
        response.put("attemptsCount", masteryResult.get("attemptsCount"));
        response.put("correctCount", masteryResult.get("correctCount"));
        response.put("sessionCompleted", sessionCompleted);
        return response;
    }

    public Map<String, Object> updateMastery(java.util.UUID userId, String level, String skill, String topic, boolean correct) {
        Map<String, Object> previous = jdbcTemplate.query("""
                SELECT attempts_count, correct_count, correct_streak, incorrect_streak
                FROM learner_skill_mastery
                WHERE user_id=? AND standard_code='NEW_HSK' AND version_code='2025'
                  AND level_code=? AND skill_key=? AND topic_key=?
                """, rs -> rs.next() ? Map.of("attempts", rs.getInt(1), "correct", rs.getInt(2),
                        "streak", rs.getInt(3), "incorrectStreak", rs.getInt(4)) : Map.of(),
                userId, level, skill, topic);
        int attempts = (int) previous.getOrDefault("attempts", 0) + 1;
        int correctCount = (int) previous.getOrDefault("correct", 0) + (correct ? 1 : 0);
        int streak = correct ? (int) previous.getOrDefault("streak", 0) + 1 : 0;
        double mastery = (correctCount + 1.0) / (attempts + 2.0); // Bayesian smoothing avoids overreacting to one answer.
        int intervalDays = !correct || mastery < .60 ? 0 : streak == 1 ? 1 : streak == 2 ? 3 : streak <= 4 ? 7 : 14;
        LocalDateTime nextReview = !correct || mastery < .60 ? LocalDateTime.now().plusHours(4)
                : LocalDateTime.now().plusDays(intervalDays);
        jdbcTemplate.update("""
                INSERT INTO learner_skill_mastery
                  (user_id,standard_code,version_code,level_code,skill_key,topic_key,
                   attempts_count,correct_count,mastery_score,correct_streak,incorrect_streak,
                   interval_days,last_answered_at,next_review_at,updated_at)
                VALUES (?,'NEW_HSK','2025',?,?,?,?,?,?,?, ?, ?,NOW(),?,NOW())
                ON CONFLICT (user_id,standard_code,version_code,level_code,skill_key,topic_key)
                DO UPDATE SET attempts_count=EXCLUDED.attempts_count, correct_count=EXCLUDED.correct_count,
                  mastery_score=EXCLUDED.mastery_score, correct_streak=EXCLUDED.correct_streak,
                  incorrect_streak=CASE WHEN ? THEN 0 ELSE learner_skill_mastery.incorrect_streak+1 END,
                  interval_days=EXCLUDED.interval_days, last_answered_at=NOW(),
                  next_review_at=EXCLUDED.next_review_at, updated_at=NOW()
                """, userId, level, skill, topic, attempts, correctCount, mastery, streak,
                correct ? 0 : (int) previous.getOrDefault("incorrectStreak", 0) + 1,
                intervalDays, Timestamp.valueOf(nextReview), correct);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("attemptsCount", attempts);
        result.put("correctCount", correctCount);
        result.put("streak", streak);
        result.put("masteryScore", Math.round(mastery * 1000.0) / 1000.0);
        result.put("intervalDays", intervalDays);
        result.put("nextReviewAt", nextReview.toString());
        return result;
    }

    @Transactional(readOnly = true)
    public MasterySummaryResponse getMasterySummary(java.util.UUID userId) {
        // 1. Overall stats
        Map<String, Object> overall = jdbcTemplate.query("""
                SELECT
                    COALESCE(SUM(attempts_count), 0) AS total_attempts,
                    COALESCE(SUM(correct_count), 0) AS total_correct,
                    COALESCE(AVG(mastery_score), 0.0) AS avg_mastery,
                    COALESCE(MAX(correct_streak), 0) AS best_streak,
                    COALESCE(COUNT(*) FILTER (WHERE next_review_at <= NOW()), 0) AS due_count,
                    COUNT(DISTINCT topic_key) AS topics_count
                FROM learner_skill_mastery
                WHERE user_id=?
                """, rs -> rs.next() ? Map.of(
                        "totalAttempts", ((Number) rs.getObject("total_attempts")).intValue(),
                        "totalCorrect", ((Number) rs.getObject("total_correct")).intValue(),
                        "avgMastery", ((Number) rs.getObject("avg_mastery")).doubleValue(),
                        "bestStreak", ((Number) rs.getObject("best_streak")).intValue(),
                        "dueCount", ((Number) rs.getObject("due_count")).intValue(),
                        "topicsCount", ((Number) rs.getObject("topics_count")).intValue()
                ) : Map.of(), userId);

        int totalAttempts = (int) overall.getOrDefault("totalAttempts", 0);
        int totalCorrect = (int) overall.getOrDefault("totalCorrect", 0);
        double overallAccuracy = totalAttempts > 0 ? Math.round((double) totalCorrect / totalAttempts * 1000.0) / 10.0 : 0.0;
        double avgMastery = Math.round((double) overall.getOrDefault("avgMastery", 0.0) * 1000.0) / 1000.0;
        int dueCount = (int) overall.getOrDefault("dueCount", 0);
        int bestStreak = (int) overall.getOrDefault("bestStreak", 0);
        int topicsCount = (int) overall.getOrDefault("topicsCount", 0);

        // 2. Skill-by-skill stats
        Map<String, Map<String, Object>> skillRows = new java.util.HashMap<>();
        jdbcTemplate.query("""
                SELECT
                    skill_key,
                    SUM(attempts_count) AS attempts,
                    SUM(correct_count) AS correct,
                    AVG(mastery_score) AS mastery,
                    COUNT(*) FILTER (WHERE next_review_at <= NOW()) AS due,
                    MAX(correct_streak) AS streak,
                    MAX(last_answered_at) AS last_practiced
                FROM learner_skill_mastery
                WHERE user_id=?
                GROUP BY skill_key
                """, (rs) -> {
                    String skill = rs.getString("skill_key");
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("attempts", rs.getInt("attempts"));
                    m.put("correct", rs.getInt("correct"));
                    m.put("mastery", rs.getDouble("mastery"));
                    m.put("due", rs.getInt("due"));
                    m.put("streak", rs.getInt("streak"));
                    Timestamp ts = rs.getTimestamp("last_practiced");
                    m.put("lastPracticed", ts != null ? ts.toLocalDateTime().toString() : null);
                    skillRows.put(skill, m);
                }, userId);

        List<String> coreSkills = List.of("vocabulary", "phonetics", "characters", "grammar");
        Map<String, String> skillLabels = Map.of(
                "vocabulary", "Từ vựng (Vocabulary)",
                "phonetics", "Ngữ âm (Phonetics)",
                "characters", "Chữ Hán (Characters)",
                "grammar", "Ngữ pháp (Grammar)"
        );

        List<SkillMasteryStat> skills = coreSkills.stream().map(sk -> {
            Map<String, Object> row = skillRows.getOrDefault(sk, Map.of());
            int attempts = (int) row.getOrDefault("attempts", 0);
            int correct = (int) row.getOrDefault("correct", 0);
            double mastery = (double) row.getOrDefault("mastery", 0.0);
            double accuracy = attempts > 0 ? Math.round((double) correct / attempts * 1000.0) / 10.0 : 0.0;
            return SkillMasteryStat.builder()
                    .skillKey(sk)
                    .label(skillLabels.getOrDefault(sk, sk))
                    .attemptsCount(attempts)
                    .correctCount(correct)
                    .masteryScore(Math.round(mastery * 1000.0) / 1000.0)
                    .accuracyPercent(accuracy)
                    .dueReviewCount((int) row.getOrDefault("due", 0))
                    .correctStreak((int) row.getOrDefault("streak", 0))
                    .lastPracticedAt((String) row.get("lastPracticed"))
                    .build();
        }).toList();

        // 3. Level breakdown
        List<String> validLevels = List.of("1", "2", "3", "4", "5", "6", "7-9");
        Map<String, Map<String, Object>> levelRows = new java.util.HashMap<>();
        jdbcTemplate.query("""
                SELECT
                    level_code,
                    SUM(attempts_count) AS attempts,
                    SUM(correct_count) AS correct,
                    AVG(mastery_score) AS mastery,
                    COUNT(*) FILTER (WHERE next_review_at <= NOW()) AS due
                FROM learner_skill_mastery
                WHERE user_id=?
                GROUP BY level_code
                """, (rs) -> {
                    String lvl = rs.getString("level_code");
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("attempts", rs.getInt("attempts"));
                    m.put("correct", rs.getInt("correct"));
                    m.put("mastery", rs.getDouble("mastery"));
                    m.put("due", rs.getInt("due"));
                    levelRows.put(lvl, m);
                }, userId);

        List<Map<String, Object>> levels = validLevels.stream().map(lvl -> {
            Map<String, Object> row = levelRows.getOrDefault(lvl, Map.of());
            int attempts = (int) row.getOrDefault("attempts", 0);
            int correct = (int) row.getOrDefault("correct", 0);
            double mastery = (double) row.getOrDefault("mastery", 0.0);
            double accuracy = attempts > 0 ? Math.round((double) correct / attempts * 1000.0) / 10.0 : 0.0;
            Map<String, Object> res = new java.util.LinkedHashMap<>();
            res.put("levelCode", lvl);
            res.put("label", "HSK " + lvl);
            res.put("attemptsCount", attempts);
            res.put("correctCount", correct);
            res.put("accuracyPercent", accuracy);
            res.put("masteryScore", Math.round(mastery * 1000.0) / 1000.0);
            res.put("dueReviewCount", (int) row.getOrDefault("due", 0));
            return res;
        }).toList();

        // 4. Recent sessions
        List<Map<String, Object>> recentSessions = jdbcTemplate.query("""
                SELECT ps.id, ps.level_code, ps.mode, ps.session_state, ps.started_at, ps.finished_at,
                       COUNT(qa.id) AS total_answered,
                       COUNT(qa.id) FILTER (WHERE qa.is_correct=true) AS correct_answered
                FROM practice_sessions ps
                LEFT JOIN question_attempts qa ON qa.session_id=ps.id
                WHERE ps.user_id=?
                GROUP BY ps.id, ps.level_code, ps.mode, ps.session_state, ps.started_at, ps.finished_at
                ORDER BY ps.started_at DESC
                LIMIT 5
                """, (rs, rowNum) -> {
                    Map<String, Object> s = new java.util.LinkedHashMap<>();
                    s.put("sessionId", rs.getLong("id"));
                    s.put("levelCode", rs.getString("level_code"));
                    s.put("mode", rs.getString("mode"));
                    s.put("state", rs.getString("session_state"));
                    Timestamp st = rs.getTimestamp("started_at");
                    s.put("startedAt", st != null ? st.toLocalDateTime().toString() : null);
                    Timestamp ft = rs.getTimestamp("finished_at");
                    s.put("finishedAt", ft != null ? ft.toLocalDateTime().toString() : null);
                    s.put("totalAnswered", rs.getInt("total_answered"));
                    s.put("correctAnswered", rs.getInt("correct_answered"));
                    return s;
                }, userId);

        return MasterySummaryResponse.builder()
                .totalAttempts(totalAttempts)
                .totalCorrect(totalCorrect)
                .overallAccuracy(overallAccuracy)
                .averageMastery(avgMastery)
                .dueReviewCount(dueCount)
                .bestStreak(bestStreak)
                .learnedTopicsCount(topicsCount)
                .skills(skills)
                .levels(levels)
                .recentSessions(recentSessions)
                .build();
    }

    private static String questionType(String skill) {
        return switch (skill) {
            case "grammar" -> "GRAMMAR_SELECT";
            case "phonetics" -> "PHONETICS_SELECT";
            case "characters" -> "MEANING_HANZI";
            default -> "VOCAB_MEANING";
        };
    }

    private static String topicFor(String skill, Object topic) {
        String supplied = string(topic);
        if (!supplied.isBlank()) return supplied.length() <= 160 ? supplied : supplied.substring(0, 160);
        return switch (skill) {
            case "grammar" -> "grammar_general";
            case "phonetics" -> "pronunciation_general";
            case "characters" -> "characters_general";
            default -> "vocabulary_general";
        };
    }

    private static String selectionReason(int priority) {
        return switch (priority) {
            case 0 -> "DUE_REVIEW";
            case 1 -> "WEAK_SKILL";
            default -> "NEW_CONTENT";
        };
    }

    private static List<?> asList(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static String normalise(String value) { return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT); }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
