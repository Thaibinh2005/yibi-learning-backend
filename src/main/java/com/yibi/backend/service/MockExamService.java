package com.yibi.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.service.plan.PathQuiz;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;

/**
 * A timed practice exam on the computer: listening, reading and typing Chinese, made from the path's own material.
 * It imitates how the computer-based test works; it is not an official HSK paper. As with quizzes, the same seed
 * gives the same paper, so it is graded by generating it again.
 */
@Service
@RequiredArgsConstructor
public class MockExamService {

    public static final int DURATION_MINUTES = 25;
    private static final int PASS_PERCENT = 60;
    private static final int PASSAGES_PER_SECTION = 2;
    private static final int TYPING_QUESTIONS = 10;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PersonalizedLearningService learningService;
    private final QuestionBankService questionBankService;

    public record ExamQuestion(String id, String kind, String prompt, List<String> options) { }

    public record Passage(String title, String text, String audioUrl, List<ExamQuestion> questions) { }

    public record Section(String title, String kind, List<Passage> passages) { }

    public record Paper(long seed, int level, int durationMinutes, int passPercent, List<Section> sections) { }

    public record Review(String id, boolean correct, String answer, String explanation) { }

    /** @param targetScore set when this level is the one the learner's HSK target is about */
    public record Result(int scorePercent, int correct, int total, boolean passed, List<Review> review,
                         int estimatedScore, int scale, Integer targetScore) { }

    private record Key(String answer, String skill, String explanation) { }

    private record Built(Paper paper, Map<String, Key> keys) { }

    @Transactional(readOnly = true)
    public Paper start(UUID userId, int level) {
        requireAllowed(userId, level);
        return build(level, new Random().nextInt(Integer.MAX_VALUE)).paper();
    }

    @Transactional
    public Result submit(UUID userId, int level, long seed, Map<String, String> answers) {
        requireAllowed(userId, level);
        Map<String, Key> keys = build(level, seed).keys();
        String levelCode = level >= 7 ? "7-9" : String.valueOf(level);
        int correct = 0;
        List<Review> review = new ArrayList<>();
        for (Map.Entry<String, Key> entry : keys.entrySet()) {
            Key key = entry.getValue();
            boolean right = key.answer().equals(String.valueOf(answers.get(entry.getKey())).trim());
            if (right) correct++;
            review.add(new Review(entry.getKey(), right, key.answer(), key.explanation()));
            jdbc.update("INSERT INTO path_answers (user_id, skill_key, is_correct) VALUES (?, ?, ?)", userId, key.skill(), right);
            questionBankService.updateMastery(userId, levelCode, key.skill(), "general", right);
        }
        int score = keys.isEmpty() ? 0 : Math.round(correct * 100f / keys.size());
        jdbc.update("INSERT INTO mock_exam_results (user_id, hsk_level, score_percent) VALUES (?, ?, ?)", userId, level, score);
        int scale = com.yibi.backend.service.plan.ScoreGoal.scaleOfLevel(level);
        Integer target = jdbc.query("SELECT target_score FROM learning_paths WHERE user_id = ? AND goal_type = 'HSK' AND target_hsk_level = ?",
                rs -> rs.next() ? (Integer) rs.getObject(1) : null, userId, level);
        return new Result(score, correct, keys.size(), score >= PASS_PERCENT, review,
                com.yibi.backend.service.plan.ScoreGoal.estimatedScore(score, scale), scale, target);
    }

    /** Candidates for the on-screen pinyin keyboard, exact readings first, then the most common words. */
    @Transactional(readOnly = true)
    public List<String> pinyinCandidates(String typed) {
        String query = typed == null ? "" : typed.toLowerCase(Locale.ROOT).replace('ü', 'v').replaceAll("[^a-z]", "");
        if (query.isEmpty() || query.length() > 30) return List.of();
        return jdbc.queryForList("""
                WITH readings AS (
                  SELECT regexp_replace(v.simplified, '[0-9]+$', '') AS word, v.frequency,
                         replace(replace(translate(lower(r.pinyin), 'āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜü', 'aaaaeeeeiiiioooouuuuvvvvv'), ' ', ''), '''', '') AS key
                  FROM hsk_vocabulary_readings r JOIN hsk_vocabulary_records v ON v.raw_record_id = r.raw_record_id
                  WHERE v.simplified !~ '[A-Za-z（(]'
                )
                SELECT word FROM readings WHERE key LIKE ? || '%'
                GROUP BY word ORDER BY bool_or(key = ?) DESC, max(frequency) DESC NULLS LAST, length(word), word
                LIMIT 8
                """, String.class, query, query);
    }

    private void requireAllowed(UUID userId, int level) {
        if (level < 1 || level > learningService.pathLevel(userId)) {
            throw new BadRequestException("Chỉ có thể thi thử ở cấp hiện tại của bạn hoặc thấp hơn.");
        }
    }

    private Built build(int level, long seed) {
        Random random = new Random(seed);
        Map<String, Key> keys = new LinkedHashMap<>();
        List<Section> sections = new ArrayList<>();
        // Reading and listening share their passages, so a paper never uses one twice: its text would give the answers away.
        Set<String> usedTitles = new HashSet<>();
        sections.add(new Section("Nghe hiểu", "LISTENING", passages("LISTENING", "listening", "n", level, random, keys, usedTitles)));
        sections.add(new Section("Đọc hiểu", "READING", passages("READING", "reading", "d", level, random, keys, usedTitles)));
        sections.add(new Section("Gõ tiếng Trung", "TYPING", List.of(new Passage(null, null, null, typing(level, random, keys)))));
        return new Built(new Paper(seed, level, DURATION_MINUTES, PASS_PERCENT, sections), keys);
    }

    private List<Passage> passages(String type, String skill, String prefix, int level, Random random, Map<String, Key> keys, Set<String> usedTitles) {
        List<Map<String, Object>> nodes = new ArrayList<>(jdbc.query("""
                SELECT n.content::text FROM path_nodes n JOIN path_units u ON u.id = n.unit_id
                WHERE u.hsk_level = ? AND n.node_type = ? ORDER BY n.id
                """, (rs, i) -> parse(rs.getString(1)), level, type));
        Collections.shuffle(nodes, random);
        nodes.removeIf(node -> usedTitles.contains(text(node.get("title"))));
        // At most half of what is left, so the next section still has passages on a level with few of them.
        int count = "LISTENING".equals(type) ? Math.min(PASSAGES_PER_SECTION, Math.max(1, nodes.size() / 2)) : Math.min(PASSAGES_PER_SECTION, nodes.size());
        List<Passage> result = new ArrayList<>();
        for (int p = 0; p < count; p++) {
            Map<String, Object> node = nodes.get(p);
            usedTitles.add(text(node.get("title")));
            List<ExamQuestion> questions = new ArrayList<>();
            for (Map<String, Object> raw : list(node.get("questions"))) {
                if (!(raw.get("options") instanceof List<?> rawOptions)) continue;
                List<String> options = new ArrayList<>(rawOptions.stream().map(String::valueOf).toList());
                String answer = text(raw.get("answer"));
                if (!options.contains(answer)) continue;
                Collections.shuffle(options, random);
                String id = prefix + p + "-" + questions.size();
                questions.add(new ExamQuestion(id, "CHOICE", text(raw.get("question")), options));
                keys.put(id, new Key(answer, skill, text(raw.get("explanation"))));
            }
            boolean listening = "LISTENING".equals(type);
            String body = text(node.get("text")).isEmpty() ? text(node.get("transcript")) : text(node.get("text"));
            String audio = node.get("audio_url") instanceof String url ? url : null;
            // A listening passage keeps its text only when there is no audio to read it out from.
            result.add(new Passage(text(node.get("title")), listening && audio != null ? null : body, audio, questions));
        }
        return result;
    }

    private List<ExamQuestion> typing(int level, Random random, Map<String, Key> keys) {
        List<Map<String, Object>> words = new ArrayList<>();
        jdbc.query("""
                SELECT n.content->'words' FROM path_nodes n JOIN path_units u ON u.id = n.unit_id
                WHERE u.hsk_level = ? AND n.node_type = 'LESSON' ORDER BY n.id
                """, rs -> { words.addAll(parseList(rs.getString(1))); }, level);
        Collections.shuffle(words, random);
        List<ExamQuestion> questions = new ArrayList<>();
        for (Map<String, Object> word : words) {
            if (questions.size() >= TYPING_QUESTIONS) break;
            String hanzi = text(word.get("simplified"));
            String pinyin = text(word.get("pinyin"));
            String meaning = text(word.get("meaning_vi"));
            String shortMeaning = PathQuiz.shorten(meaning);
            if (hanzi.isEmpty() || pinyin.isEmpty() || shortMeaning.isEmpty()) continue;
            String id = "g" + questions.size();
            questions.add(new ExamQuestion(id, "TYPING", pinyin + " · " + shortMeaning, List.of()));
            keys.put(id, new Key(hanzi, "writing", hanzi + " (" + pinyin + "): " + meaning));
        }
        return questions;
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new IllegalStateException("Bad path node content", ex);
        }
    }

    private List<Map<String, Object>> parseList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new IllegalStateException("Bad path node content", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
