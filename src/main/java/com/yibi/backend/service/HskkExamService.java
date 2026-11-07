package com.yibi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.service.plan.HskkPaper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** HSKK practice: a learner records an answer per question, then hands the attempt in for an estimated score. The audio is not kept. */
@Service
@RequiredArgsConstructor
public class HskkExamService {

    private static final List<String> TIERS = List.of(HskkPaper.PRIMARY, HskkPaper.INTERMEDIATE, HskkPaper.ADVANCED);
    private static final int REPEAT_PASS = 70;

    public record Tier(String tier, boolean open, int requiredLevel, Integer bestScore, Integer attempts) { }

    public record Status(boolean speechReady, List<Tier> tiers, Integer targetScore) { }

    public record QuestionView(String id, String kind, String listenText, String showText, int prepSeconds, int answerSeconds) { }

    public record PartView(String title, String instruction, List<QuestionView> questions) { }

    public record Paper(long attemptId, String tier, List<PartView> parts) { }

    public record Answered(String questionId, int score) { }

    public record ReviewItem(String questionId, String kind, String reference, String transcript, int score, boolean answered) { }

    public record PartResult(String title, int weight, int averageScore, List<ReviewItem> items) { }

    /** @param targetScore the learner's own target, when the goal is HSKK */
    public record Result(int scorePercent, boolean passed, int passPercent, Integer targetScore, Boolean reachedTarget,
                         int answered, int total, List<PartResult> parts) { }

    private record Attempt(UUID userId, String tier, int contentLevel, long seed, boolean finished) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SpeechScoringClient speech;
    private final PersonalizedLearningService learningService;
    private final QuestionBankService questionBankService;

    public Status status(UUID userId) {
        int level = learningService.pathLevel(userId);
        List<Tier> tiers = new ArrayList<>();
        for (String tier : TIERS) {
            Map<String, Object> history = jdbc.queryForMap(
                    "SELECT max(score_percent) AS best, count(*) AS attempts FROM hskk_attempts WHERE user_id = ? AND tier = ? AND finished_at IS NOT NULL", userId, tier);
            tiers.add(new Tier(tier, level >= HskkPaper.requiredLevel(tier), HskkPaper.requiredLevel(tier),
                    history.get("best") == null ? null : ((Number) history.get("best")).intValue(),
                    ((Number) history.get("attempts")).intValue()));
        }
        return new Status(speech.isAvailable(), tiers, hskkTarget(userId));
    }

    public Paper start(UUID userId, String rawTier) {
        String tier = rawTier == null ? "" : rawTier.toUpperCase();
        if (!TIERS.contains(tier)) throw new BadRequestException("Không có cấp HSKK này.");
        int level = learningService.pathLevel(userId);
        if (level < HskkPaper.requiredLevel(tier)) throw new BadRequestException("Cấp HSKK này cao hơn trình độ hiện tại của bạn.");
        int contentLevel = HskkPaper.contentLevel(tier, level);
        long seed = new Random().nextInt(Integer.MAX_VALUE);
        List<HskkPaper.Part> parts = HskkPaper.build(tier, material(tier, contentLevel), seed);
        if (parts.stream().anyMatch(part -> part.questions().isEmpty())) {
            throw new BadRequestException("Chưa đủ nội dung để tạo đề ở cấp này.");
        }
        Long attemptId = jdbc.queryForObject(
                "INSERT INTO hskk_attempts (user_id, tier, content_level, seed) VALUES (?, ?, ?, ?) RETURNING id", Long.class, userId, tier, contentLevel, seed);
        return new Paper(attemptId, tier, parts.stream().map(part -> new PartView(part.title(), part.instruction(),
                part.questions().stream().map(q -> new QuestionView(q.id(), q.kind(), q.listenText(), q.showText(), q.prepSeconds(), q.answerSeconds())).toList())).toList());
    }

    /** The slow speech call happens outside any transaction; only its result is stored. */
    public Answered answer(UUID userId, long attemptId, String questionId, byte[] audio, String filename) {
        Attempt attempt = load(userId, attemptId);
        if (attempt.finished()) throw new BadRequestException("Bài này đã nộp.");
        HskkPaper.Question question = paperOf(attempt).stream().flatMap(part -> part.questions().stream())
                .filter(q -> q.id().equals(questionId)).findFirst()
                .orElseThrow(() -> new BadRequestException("Câu hỏi không thuộc đề này."));
        SpeechScoringClient.Heard heard = speech.assess(audio, filename, question.reference());
        int score = HskkPaper.score(question, heard.accuracy(), heard.transcript());
        jdbc.update("""
                INSERT INTO hskk_answers (attempt_id, question_id, score, transcript) VALUES (?, ?, ?, ?)
                ON CONFLICT (attempt_id, question_id) DO UPDATE SET score = EXCLUDED.score, transcript = EXCLUDED.transcript, answered_at = NOW()
                """, attemptId, questionId, score, heard.transcript());
        jdbc.update("INSERT INTO path_answers (user_id, skill_key, is_correct) VALUES (?, 'speaking', ?)", userId, score >= REPEAT_PASS);
        String levelCode = attempt.contentLevel() >= 7 ? "7-9" : String.valueOf(attempt.contentLevel());
        questionBankService.updateMastery(userId, levelCode, "speaking", "general", score >= REPEAT_PASS);
        return new Answered(questionId, score);
    }

    public Result finish(UUID userId, long attemptId) {
        Attempt attempt = load(userId, attemptId);
        List<HskkPaper.Part> parts = paperOf(attempt);
        Map<String, Map<String, Object>> answers = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT question_id, score, transcript FROM hskk_answers WHERE attempt_id = ?", attemptId)) {
            answers.put((String) row.get("question_id"), row);
        }
        Map<String, Integer> scores = new HashMap<>();
        answers.forEach((id, row) -> scores.put(id, ((Number) row.get("score")).intValue()));
        int total = HskkPaper.total(parts, scores);
        jdbc.update("UPDATE hskk_attempts SET score_percent = ?, finished_at = COALESCE(finished_at, NOW()) WHERE id = ?", total, attemptId);

        List<PartResult> results = new ArrayList<>();
        int questions = 0;
        for (HskkPaper.Part part : parts) {
            List<ReviewItem> items = new ArrayList<>();
            int sum = 0;
            for (HskkPaper.Question q : part.questions()) {
                Map<String, Object> row = answers.get(q.id());
                int score = scores.getOrDefault(q.id(), 0);
                sum += score;
                items.add(new ReviewItem(q.id(), q.kind(), q.reference(), row == null ? null : (String) row.get("transcript"), score, row != null));
            }
            questions += part.questions().size();
            results.add(new PartResult(part.title(), part.weight(), part.questions().isEmpty() ? 0 : Math.round(sum / (float) part.questions().size()), items));
        }
        Integer target = hskkTarget(userId);
        return new Result(total, total >= HskkPaper.PASS_PERCENT, HskkPaper.PASS_PERCENT, target, target == null ? null : total >= target, answers.size(), questions, results);
    }

    private Integer hskkTarget(UUID userId) {
        return jdbc.query("SELECT target_score FROM learning_paths WHERE user_id = ? AND goal_type = 'HSKK'",
                rs -> rs.next() ? (Integer) rs.getObject(1) : null, userId);
    }

    private Attempt load(UUID userId, long attemptId) {
        return jdbc.query("SELECT user_id, tier, content_level, seed, finished_at FROM hskk_attempts WHERE id = ? AND user_id = ?", rs -> {
            if (!rs.next()) throw new ResourceNotFoundException("HSKK attempt", "id", attemptId);
            return new Attempt(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getObject(5) != null);
        }, attemptId, userId);
    }

    private List<HskkPaper.Part> paperOf(Attempt attempt) {
        return HskkPaper.build(attempt.tier(), material(attempt.tier(), attempt.contentLevel()), attempt.seed());
    }

    /** Ordered, so the same seed always rebuilds the same paper. */
    private HskkPaper.Material material(String tier, int contentLevel) {
        int lowest = HskkPaper.requiredLevel(tier);
        int[] length = HskkPaper.repeatLength(tier);
        List<String> lines = new ArrayList<>();
        List<String> passages = new ArrayList<>();
        if (HskkPaper.ADVANCED.equals(tier)) {
            jdbc.query("""
                    SELECT n.content::text FROM path_nodes n JOIN path_units u ON u.id = n.unit_id
                    WHERE n.node_type = 'READING' AND u.hsk_level BETWEEN ? AND ? ORDER BY n.id
                    """, rs -> {
                String text = field(rs.getString(1), "text");
                if (HskkPaper.hanCount(text) >= 80) passages.add(text);
            }, lowest, contentLevel);
        } else {
            jdbc.query("""
                    SELECT line->>'zh' FROM path_nodes n JOIN path_units u ON u.id = n.unit_id, jsonb_array_elements(n.content->'lines') line
                    WHERE n.node_type = 'DIALOGUE' AND u.hsk_level BETWEEN ? AND ? ORDER BY n.id, line->>'zh'
                    """, rs -> {
                String line = rs.getString(1);
                int han = HskkPaper.hanCount(line);
                if (han >= length[0] && han <= length[1] && !lines.contains(line)) lines.add(line);
            }, Math.min(lowest, contentLevel), contentLevel);
        }
        return new HskkPaper.Material(lines, passages);
    }

    private String field(String json, String name) {
        try {
            JsonNode value = objectMapper.readTree(json).get(name);
            return value == null ? "" : value.asText();
        } catch (Exception ex) {
            return "";
        }
    }
}
