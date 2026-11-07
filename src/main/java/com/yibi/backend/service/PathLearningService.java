package com.yibi.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.repository.SrsCardRepository;
import com.yibi.backend.service.plan.LessonPlan;
import com.yibi.backend.service.plan.PathQuiz;
import com.yibi.backend.service.plan.Roadmap;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;

/** Opens path nodes, runs their quizzes, and moves the learner on when they pass. */
@Service
@RequiredArgsConstructor
public class PathLearningService {

    /** Only a unit test has a score to reach; any other node is passed by finishing it. */
    public static final int PASS_PERCENT = 80;
    private static final String CHECKPOINT = "CHECKPOINT";
    private static final String LESSON = "LESSON";
    private static final int NODE_QUESTIONS = 8;
    private static final int CHECKPOINT_QUESTIONS = 10;
    private static final int LEVEL_TEST_QUESTIONS = 25;
    private static final int MAX_LEVEL = 7;
    /** Old mistakes added on top of a node's own questions; they do not count towards its score. */
    private static final int MISTAKES_PER_QUIZ = 2;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PersonalizedLearningService learningService;
    private final QuestionBankService questionBankService;
    private final SrsService srsService;
    private final SrsCardRepository srsCardRepository;
    private final SpeechScoringClient speechClient;
    private final PathMistakeService mistakes;

    @org.springframework.beans.factory.annotation.Value("${app.speech.pass-accuracy:0.7}")
    private double speakingPassAccuracy;

    public record NodeDetail(long id, String title, String type, int hskLevel, String unitTitle, String status,
                             int bestScore, Map<String, Object> content) { }

    /** A question as the learner sees it: no answer. */
    public record QuizQuestion(String id, String type, String prompt, String pinyin, String audioText, List<String> options,
                               boolean fromMistakes) { }

    /** @param steps the order of a woven lesson; null for a node that only asks its questions */
    public record Quiz(long seed, int passPercent, List<QuizQuestion> questions, List<LessonPlan.Step> steps) { }

    public record Checked(boolean correct, String correctAnswer, String explanation) { }

    /**
     * @param mistakesAdded    questions of this quiz answered wrongly, saved to come back later
     * @param mistakesReviewed old mistakes asked again in this quiz
     * @param mistakesResolved mistakes that this quiz closed
     */
    public record Outcome(int scorePercent, int correct, int total, boolean passed, int cardsAdded,
                          Long nextNodeId, Integer newLevel, int mistakesAdded, int mistakesReviewed, int mistakesResolved) { }

    private record Recorded(int correct, int mistakesAdded, int mistakesResolved) { }

    /** @param marks one per character of the line: right, wrong, or not spoken (punctuation) */
    public record Spoken(double accuracy, boolean passed, List<Map<String, String>> marks, String heard) { }

    private record NodeRow(long id, String title, String type, int level, long unitId, String unitTitle, Map<String, Object> content) { }

    @Transactional(readOnly = true)
    public NodeDetail getNode(UUID userId, long nodeId) {
        NodeRow node = load(nodeId);
        String status = requireOpen(userId, node);
        Integer best = jdbc.query("SELECT best_score FROM user_path_progress WHERE user_id = ? AND node_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, userId, nodeId);
        return new NodeDetail(node.id(), node.title(), node.type(), node.level(), node.unitTitle(), status,
                best == null ? 0 : best, node.content());
    }

    @Transactional(readOnly = true)
    public Quiz startQuiz(UUID userId, long nodeId) {
        NodeRow node = load(nodeId);
        requireOpen(userId, node);
        boolean lesson = LESSON.equals(node.type());
        long seed = lesson ? LessonPlan.seed(learningService.goalType(userId), learningService.urgentSkills(userId), (int) newSeed()) : newSeed();
        List<PathQuiz.Question> questions = questionsOf(node, seed);
        List<String> keys = questions.stream().map(q -> PathMistakeService.keyOf(q.prompt(), q.answer())).toList();
        List<QuizQuestion> asked = new ArrayList<>(toQuiz(seed, 0, questions).questions());
        List<LessonPlan.Step> steps = lesson ? new ArrayList<>(lessonPlan(node, seed).steps()) : null;
        Random random = new Random(seed);
        for (PathMistakeService.Mistake mistake : mistakes.dueForQuiz(userId, node.level(), MISTAKES_PER_QUIZ, keys)) {
            QuizQuestion again = askAgain(mistake, random);
            asked.add(random.nextInt(asked.size() + 1), again);
            if (steps != null) steps.add(random.nextInt(steps.size() + 1), new LessonPlan.Step(LessonPlan.QUESTION, again.id(), null, null));
        }
        return new Quiz(seed, CHECKPOINT.equals(node.type()) ? passPercentOf(userId) : 0, asked, steps);
    }

    @Transactional(readOnly = true)
    public Checked check(UUID userId, long nodeId, long seed, String questionId, String answer) {
        NodeRow node = load(nodeId);
        requireOpen(userId, node);
        if (CHECKPOINT.equals(node.type())) throw new BadRequestException("Bài kiểm tra chỉ có kết quả sau khi nộp bài.");
        if (PathMistakeService.isMistakeId(questionId)) return checkMistake(userId, questionId, answer);
        return checkAgainst(questionsOf(node, seed), questionId, answer);
    }

    /** Up to one review's worth of open mistakes. Nothing to pass here: it only shortens the list. */
    @Transactional(readOnly = true)
    public Quiz startMistakesReview(UUID userId) {
        Random random = new Random();
        List<QuizQuestion> asked = mistakes.dueForQuiz(userId, MAX_LEVEL, com.yibi.backend.service.plan.DailyPlanner.MISTAKES_PER_REVIEW, List.of())
                .stream().map(mistake -> askAgain(mistake, random)).toList();
        return new Quiz(0, 0, asked, null);
    }

    @Transactional(readOnly = true)
    public Checked checkMistake(UUID userId, String questionId, String answer) {
        PathMistakeService.Mistake mistake = mistakes.load(userId, questionId);
        return new Checked(mistake.answer().equals(answer), mistake.answer(), mistake.explanation());
    }

    @Transactional
    public Outcome submitMistakesReview(UUID userId, Map<String, String> answers) {
        int[] tally = gradeMistakes(userId, answers);
        return new Outcome(PathQuiz.scorePercent(tally[0], tally[1]), tally[0], tally[1], true, 0, null, null, 0, tally[1], tally[2]);
    }

    private static QuizQuestion askAgain(PathMistakeService.Mistake mistake, Random random) {
        List<String> options = new ArrayList<>(mistake.options());
        Collections.shuffle(options, random);
        return new QuizQuestion(mistake.questionId(), mistake.type(), mistake.prompt(), mistake.pinyin(), mistake.audioText(), options, true);
    }

    /** @return right answers, mistakes answered, and mistakes this closed */
    private int[] gradeMistakes(UUID userId, Map<String, String> answers) {
        int right = 0;
        int answered = 0;
        int resolved = 0;
        for (Map.Entry<String, String> entry : answers.entrySet()) {
            if (!PathMistakeService.isMistakeId(entry.getKey())) continue;
            PathMistakeService.Mistake mistake = mistakes.load(userId, entry.getKey());
            boolean correct = mistake.answer().equals(entry.getValue());
            if (correct) right++;
            answered++;
            if (mistakes.answerAgain(userId, mistake, entry.getValue())) resolved++;
            jdbc.update("INSERT INTO path_answers (user_id, skill_key, is_correct) VALUES (?, ?, ?)", userId, mistake.skill(), correct);
            questionBankService.updateMastery(userId, mistake.level() >= MAX_LEVEL ? "7-9" : String.valueOf(mistake.level()), mistake.skill(), "general", correct);
        }
        return new int[] {right, answered, resolved};
    }

    @Transactional
    public Outcome submit(UUID userId, long nodeId, long seed, Map<String, String> answers) {
        NodeRow node = load(nodeId);
        requireOpen(userId, node);
        List<PathQuiz.Question> questions = questionsOf(node, seed);
        Recorded recorded = recordAnswers(userId, node.id(), node.level(), questions, answers);
        int correct = recorded.correct();
        int[] again = gradeMistakes(userId, answers);
        int score = PathQuiz.scorePercent(correct, questions.size());
        boolean passed = CHECKPOINT.equals(node.type()) ? score >= passPercentOf(userId)
                : questions.stream().allMatch(question -> answers.containsKey(question.id()));
        jdbc.update("""
                INSERT INTO user_path_progress (user_id, node_id, best_score, attempts, completed_at)
                VALUES (?, ?, ?, 1, CASE WHEN ? THEN NOW() END)
                ON CONFLICT (user_id, node_id) DO UPDATE SET
                  best_score = GREATEST(user_path_progress.best_score, EXCLUDED.best_score),
                  attempts = user_path_progress.attempts + 1,
                  completed_at = COALESCE(user_path_progress.completed_at, EXCLUDED.completed_at)
                """, userId, nodeId, score, passed);

        int cards = passed && LESSON.equals(node.type()) ? addToReviewDeck(userId, node.content()) : 0;
        Integer newLevel = passed ? raiseLevelIfFinished(userId, node.level()) : null;
        return new Outcome(score, correct, questions.size(), passed, cards, passed ? nextNode(userId, node.level()) : null, newLevel,
                recorded.mistakesAdded(), again[1], recorded.mistakesResolved() + again[2]);
    }

    /** The slow speech call runs outside any transaction; only the result is stored. */
    public Spoken speak(UUID userId, long nodeId, int lineIndex, byte[] audio, String filename) {
        NodeRow node = load(nodeId);
        requireOpen(userId, node);
        List<Map<String, Object>> lines = list(node.content().get("lines"));
        if (lineIndex < 0 || lineIndex >= lines.size()) throw new BadRequestException("Câu thoại không thuộc bài này.");
        String target = text(lines.get(lineIndex).get("zh"));
        SpeechScoringClient.Heard heard = speechClient.assess(audio, filename, target);
        com.yibi.backend.service.plan.SpeechAlignment.Result aligned = com.yibi.backend.service.plan.SpeechAlignment.align(target, heard.transcript());
        double accuracy = aligned.accuracy();
        boolean passed = accuracy >= speakingPassAccuracy;
        jdbc.update("INSERT INTO path_answers (user_id, node_id, skill_key, is_correct) VALUES (?, ?, 'speaking', ?)", userId, nodeId, passed);
        questionBankService.updateMastery(userId, node.level() >= MAX_LEVEL ? "7-9" : String.valueOf(node.level()), "speaking", "general", passed);
        return new Spoken(accuracy, passed, aligned.marks(), heard.transcript());
    }

    /** A test on the whole level; passing it moves the learner up without doing every node. */
    @Transactional(readOnly = true)
    public Quiz startLevelTest(UUID userId, int level) {
        requireLevelTest(userId, level);
        long seed = newSeed();
        return toQuiz(seed, passPercentOf(userId), levelTestQuestions(level, seed));
    }

    @Transactional
    public Outcome submitLevelTest(UUID userId, int level, long seed, Map<String, String> answers) {
        requireLevelTest(userId, level);
        List<PathQuiz.Question> questions = levelTestQuestions(level, seed);
        int correct = recordAnswers(userId, null, level, questions, answers).correct();
        int score = PathQuiz.scorePercent(correct, questions.size());
        boolean passed = score >= passPercentOf(userId);
        Integer newLevel = passed ? setLevel(userId, level + 1) : null;
        return new Outcome(score, correct, questions.size(), passed, 0, null, newLevel, 0, 0, 0);
    }

    private void requireLevelTest(UUID userId, int level) {
        if (level != learningService.pathLevel(userId)) throw new BadRequestException("Chỉ có thể kiểm tra vượt cấp ở cấp hiện tại của bạn.");
        if (level >= MAX_LEVEL) throw new BadRequestException("Đây đã là cấp cao nhất của lộ trình.");
    }

    /** Kept within int range: a full long would lose precision as a JSON number in the browser. */
    private static long newSeed() {
        return new Random().nextInt(Integer.MAX_VALUE);
    }

    private String requireOpen(UUID userId, NodeRow node) {
        Roadmap.Result path = learningService.getRoadmap(userId, node.level());
        String status = path.units().stream().flatMap(unit -> unit.nodes().stream())
                .filter(candidate -> candidate.id() == node.id()).map(Roadmap.Node::status).findFirst().orElse(Roadmap.LOCKED);
        if (Roadmap.LOCKED.equals(status)) throw new BadRequestException("Bài này chưa mở khóa. Hãy hoàn thành bài trước đó.");
        return status;
    }

    /** A learner aiming for a high score has to show it on the tests of the path too. */
    private int passPercentOf(UUID userId) {
        return jdbc.query("SELECT goal_type, target_hsk_level, target_score FROM learning_paths WHERE user_id = ?",
                rs -> rs.next() ? com.yibi.backend.service.plan.ScoreGoal.passPercent(rs.getString(1), rs.getInt(2), (Integer) rs.getObject(3)) : PASS_PERCENT, userId);
    }

    private Quiz toQuiz(long seed, int passPercent, List<PathQuiz.Question> questions) {
        return new Quiz(seed, passPercent, questions.stream()
                .map(q -> new QuizQuestion(q.id(), q.type(), q.prompt(), q.pinyin(), q.audioText(), q.options(), false)).toList(), null);
    }

    private Checked checkAgainst(List<PathQuiz.Question> questions, String questionId, String answer) {
        PathQuiz.Question question = questions.stream().filter(q -> q.id().equals(questionId)).findFirst()
                .orElseThrow(() -> new BadRequestException("Câu hỏi không thuộc bài này."));
        return new Checked(question.answer().equals(answer), question.answer(), question.explanation());
    }

    /** A level test has no node: it may cover what was not taught yet, so missing it is not a mistake to review. */
    private Recorded recordAnswers(UUID userId, Long nodeId, int level, List<PathQuiz.Question> questions, Map<String, String> answers) {
        String levelCode = level >= MAX_LEVEL ? "7-9" : String.valueOf(level);
        int correct = 0;
        int added = 0;
        int resolved = 0;
        for (PathQuiz.Question question : questions) {
            boolean right = question.answer().equals(answers.get(question.id()));
            if (right) correct++;
            if (nodeId != null && right) {
                if (mistakes.recordRight(userId, question)) resolved++;
            } else if (nodeId != null) {
                mistakes.recordWrong(userId, nodeId, level, question, answers.get(question.id()));
                added++;
            }
            jdbc.update("INSERT INTO path_answers (user_id, node_id, skill_key, is_correct) VALUES (?, ?, ?, ?)",
                    userId, nodeId, question.skill(), right);
            questionBankService.updateMastery(userId, levelCode, question.skill(), "general", right);
        }
        return new Recorded(correct, added, resolved);
    }

    private int addToReviewDeck(UUID userId, Map<String, Object> content) {
        int added = 0;
        for (Map<String, Object> word : list(content.get("words"))) {
            if (!(word.get("raw_record_id") instanceof Number id)) continue;
            if (srsCardRepository.existsByUserIdAndHskVocabularyRecordId(userId, id.longValue())) continue;
            srsService.addCard(userId, id.longValue());
            added++;
        }
        return added;
    }

    private Integer raiseLevelIfFinished(UUID userId, int level) {
        if (level != learningService.pathLevel(userId) || level >= MAX_LEVEL) return null;
        Integer open = jdbc.queryForObject("""
                SELECT count(*) FROM path_nodes n JOIN path_units u ON u.id = n.unit_id
                WHERE u.hsk_level = ? AND NOT EXISTS (
                  SELECT 1 FROM user_path_progress p WHERE p.node_id = n.id AND p.user_id = ? AND p.completed_at IS NOT NULL)
                """, Integer.class, level, userId);
        return open != null && open == 0 ? setLevel(userId, level + 1) : null;
    }

    private Integer setLevel(UUID userId, int level) {
        jdbc.update("UPDATE users SET hsk_level = ? WHERE id = ?", level, userId);
        // A learner may go past their goal; the target can never sit below the level reached (chk_path_hsk).
        jdbc.update("UPDATE learning_paths SET current_hsk_level = ?, target_hsk_level = GREATEST(target_hsk_level, ?), generated_at = NOW() WHERE user_id = ?", level, level, userId);
        return level;
    }

    private Long nextNode(UUID userId, int level) {
        List<Roadmap.Node> open = learningService.getRoadmap(userId, level).pathOrder();
        return open.isEmpty() ? null : open.get(0).id();
    }

    private List<PathQuiz.Question> questionsOf(NodeRow node, long seed) {
        if (LESSON.equals(node.type())) return lessonPlan(node, seed).questions();
        Random random = new Random(seed);
        List<PathQuiz.Word> pool = wordPool(node.level());
        return switch (node.type()) {
            case "DIALOGUE" -> PathQuiz.forPrepared(list(node.content().get("questions")), "reading", NODE_QUESTIONS, random);
            case "READING" -> PathQuiz.forPrepared(list(node.content().get("questions")), "reading", NODE_QUESTIONS, random);
            case "LISTENING" -> PathQuiz.forPrepared(list(node.content().get("questions")), "listening", NODE_QUESTIONS, random);
            default -> checkpointQuestions(node, pool, random);
        };
    }

    private LessonPlan.Plan lessonPlan(NodeRow node, long seed) {
        List<PathQuiz.Word> pool = wordPool(node.level());
        return LessonPlan.build(node.content(), pool, characterPool(pool), seed);
    }

    /** Everything taught in the unit, in one short test. */
    private List<PathQuiz.Question> checkpointQuestions(NodeRow node, List<PathQuiz.Word> pool, Random random) {
        List<NodeRow> siblings = jdbc.query("""
                SELECT n.id, n.title, n.node_type, n.content::text FROM path_nodes n
                WHERE n.unit_id = ? AND n.node_type <> 'CHECKPOINT' ORDER BY n.node_order
                """, (rs, i) -> new NodeRow(rs.getLong(1), rs.getString(2), rs.getString(3), node.level(), node.unitId(), node.unitTitle(), parse(rs.getString(4))), node.unitId());
        List<PathQuiz.Word> unitWords = new ArrayList<>();
        List<PathQuiz.Question> prepared = new ArrayList<>();
        for (NodeRow sibling : siblings) {
            switch (sibling.type()) {
                case LESSON -> {
                    unitWords.addAll(words(sibling.content()));
                    prepared.addAll(PathQuiz.forPrepared(list(sibling.content().get("questions")), "grammar", 1, random));
                }
                case "DIALOGUE" -> prepared.addAll(PathQuiz.forPrepared(list(sibling.content().get("questions")), "reading", 1, random));
                case "READING" -> prepared.addAll(PathQuiz.forPrepared(list(sibling.content().get("questions")), "reading", 1, random));
                case "LISTENING" -> prepared.addAll(PathQuiz.forPrepared(list(sibling.content().get("questions")), "listening", 1, random));
                default -> { }
            }
        }
        // Grammar from many lessons must not crowd out the dialogue and passages.
        Collections.shuffle(prepared, random);
        List<PathQuiz.Question> all = new ArrayList<>(PathQuiz.forWords(unitWords, pool, CHECKPOINT_QUESTIONS - Math.min(prepared.size(), 4), random));
        all.addAll(prepared.subList(0, Math.min(prepared.size(), 4)));
        return PathQuiz.mix(all, CHECKPOINT_QUESTIONS, random);
    }

    private List<PathQuiz.Question> levelTestQuestions(int level, long seed) {
        Random random = new Random(seed);
        List<PathQuiz.Word> pool = wordPool(level);
        Map<String, List<Map<String, Object>>> preparedBySkill = new TreeMap<>();
        jdbc.query("""
                SELECT n.node_type, n.content->'questions' FROM path_nodes n JOIN path_units u ON u.id = n.unit_id
                WHERE u.hsk_level = ? AND n.node_type IN ('LESSON', 'READING', 'LISTENING', 'DIALOGUE') ORDER BY n.id
                """, rs -> {
            String json = rs.getString(2);
            if (json == null) return;
            String skill = switch (rs.getString(1)) {
                case LESSON -> "grammar";
                case "LISTENING" -> "listening";
                default -> "reading";
            };
            preparedBySkill.computeIfAbsent(skill, key -> new ArrayList<>()).addAll(parseList(json));
        }, level);
        List<PathQuiz.Question> all = new ArrayList<>(PathQuiz.forWords(pool, pool, 13, random));
        int perSkill = preparedBySkill.isEmpty() ? 0 : (LEVEL_TEST_QUESTIONS - all.size()) / preparedBySkill.size();
        preparedBySkill.forEach((skill, prepared) -> all.addAll(PathQuiz.forPrepared(prepared, skill, perSkill, random)));
        return PathQuiz.mix(all, LEVEL_TEST_QUESTIONS, random);
    }

    /** Wrong options come from the same level. Ordered, so a seed reproduces the same options. */
    private List<PathQuiz.Word> wordPool(int level) {
        List<PathQuiz.Word> pool = new ArrayList<>();
        jdbc.query("""
                SELECT n.content->'words' FROM path_nodes n JOIN path_units u ON u.id = n.unit_id
                WHERE u.hsk_level = ? AND n.node_type = 'LESSON' ORDER BY n.id
                """, rs -> {
            pool.addAll(toWords(parseList(rs.getString(1))));
        }, level);
        return pool;
    }

    private static List<String> characterPool(List<PathQuiz.Word> pool) {
        Set<String> characters = new LinkedHashSet<>();
        pool.forEach(word -> word.hanzi().codePoints().mapToObj(Character::toString).forEach(characters::add));
        return List.copyOf(characters);
    }

    private static List<PathQuiz.Word> words(Map<String, Object> content) {
        return toWords(list(content.get("words")));
    }

    private static List<PathQuiz.Word> toWords(List<Map<String, Object>> items) {
        return items.stream().map(item -> new PathQuiz.Word(text(item.get("simplified")), text(item.get("pinyin")),
                !text(item.get("meaning_vi")).isBlank() ? text(item.get("meaning_vi")) : text(item.get("meaning_en")))).toList();
    }

    private NodeRow load(long nodeId) {
        NodeRow node = jdbc.query("""
                SELECT n.id, n.title, n.node_type, u.hsk_level, u.id, u.title_vi, n.content::text
                FROM path_nodes n JOIN path_units u ON u.id = n.unit_id WHERE n.id = ?
                """, rs -> rs.next() ? new NodeRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getLong(5), rs.getString(6), parse(rs.getString(7))) : null, nodeId);
        if (node == null) throw new ResourceNotFoundException("Path node", "id", nodeId);
        return node;
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
