package com.yibi.backend.service;

import com.yibi.backend.dto.learningpath.*;
import com.yibi.backend.entity.*;
import com.yibi.backend.repository.*;
import lombok.RequiredArgsConstructor;
import com.yibi.backend.service.plan.DailyPlanner;
import com.yibi.backend.service.plan.Roadmap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PersonalizedLearningService {

    private static final List<SkillDefinition> SKILLS = List.of(
            new SkillDefinition("vocabulary", "Từ vựng"),
            new SkillDefinition("phonetics", "Pinyin và phát âm"),
            new SkillDefinition("characters", "Nhận diện chữ Hán"),
            new SkillDefinition("grammar", "Ngữ pháp"),
            new SkillDefinition("listening", "Nghe hiểu"),
            new SkillDefinition("reading", "Đọc hiểu"),
            new SkillDefinition("writing", "Viết câu"),
            new SkillDefinition("speaking", "Phát âm khi đọc")
    );

    /** Accuracy reflects the latest answers per skill, so improvement shows up without a new test. */
    static final int RECENT_ATTEMPTS_WINDOW = 30;
    private static final int PLAN_CANDIDATES = 12;
    private static final int MAX_COURSE_LEVEL = 7;

    /** The kind of lesson that trains each skill; passages live in READING/LISTENING lessons. */
    private static final Map<String, String> LESSON_TYPE_OF_SKILL = Map.of(
            "vocabulary", "LESSON",
            "phonetics", "LESSON",
            "characters", "LESSON",
            "grammar", "LESSON",
            "writing", "LESSON",
            "reading", "READING",
            "listening", "LISTENING",
            "speaking", "DIALOGUE");

    private final PlacementAssessmentRepository assessmentRepository;
    private final SkillAttemptRepository skillAttemptRepository;
    private final UserRepository userRepository;
    private final PlacementResultRepository placementResultRepository;
    private final SrsCardRepository srsCardRepository;
    private final LearningGoalService learningGoalService;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public LearningPathOverviewResponse getOverview(UUID userId) {
        Optional<PlacementAssessment> latest = assessmentRepository
                .findFirstByUserIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(userId);
        List<SkillStatResponse> stats = buildSkillStats(loadAttempts(userId), LocalDateTime.now());
        int level = pathLevel(userId);
        return LearningPathOverviewResponse.builder()
                .assessmentCompleted(latest.isPresent())
                .availableHskLevel(level)
                .latestScorePercent(latest.map(PlacementAssessment::getScorePercent).orElse(null))
                .levelSummary(latest.map(a -> placementResultRepository.findById(a.getId())
                                .map(this::adaptiveSummary).orElseGet(() -> levelSummary(a.getScorePercent())))
                        .orElse("Hãy làm bài đánh giá để xác định trình độ và xây dựng lộ trình học."))
                .skills(stats)
                .goal(learningGoalService.get(userId))
                .build();
    }

    /** Sizes today's work to the learner's minutes: reviews due first, then weak skills, then new lessons. */
    @Transactional(readOnly = true)
    public DailyPlanResponse getTodayPlan(UUID userId) {
        LearningGoalResponse goal = learningGoalService.get(userId);
        int level = pathLevel(userId);
        int target = Math.max(level, Math.min(goal.getTargetLevel(), MAX_COURSE_LEVEL));
        String levelCode = level == MAX_COURSE_LEVEL ? "7-9" : String.valueOf(level);

        int dueCards = (int) srsCardRepository.countDueCards(userId, LocalDate.now());
        Integer dueTopics = jdbc.queryForObject("""
                SELECT count(*) FROM learner_skill_mastery
                WHERE user_id = ? AND level_code = ? AND next_review_at <= NOW()
                """, Integer.class, userId, levelCode);
        List<Attempt> attempts = loadAttempts(userId);
        List<DailyPlanner.SkillNeed> needs = skillNeeds(attempts);
        List<Roadmap.Node> open = layOutLevel(userId, level, level, attempts).stream()
                .flatMap(unit -> unit.nodes().stream()).filter(node -> !Roadmap.DONE.equals(node.status())).toList();
        // Far enough along the path to fill any day, plus nodes with wrong answers wherever they are.
        List<DailyPlanner.LessonCandidate> candidates = java.util.stream.IntStream.range(0, open.size())
                .filter(i -> i < PLAN_CANDIDATES || open.get(i).wrongAnswers() > 0)
                .mapToObj(open::get)
                .map(node -> new DailyPlanner.LessonCandidate(node.id(), node.title(), node.type(),
                        level, node.wrongAnswers()))
                .toList();

        Integer openCount = jdbc.queryForObject("SELECT count(*) FROM path_mistakes WHERE user_id = ? AND resolved_at IS NULL", Integer.class, userId);
        int openMistakes = openCount == null ? 0 : openCount;
        DailyPlanner.Plan plan = DailyPlanner.build(new DailyPlanner.Input(goal.getDailyMinutes(), levelCode, dueCards,
                dueTopics == null ? 0 : dueTopics, needs, candidates, !attempts.isEmpty(), openMistakes));

        Map<String, Integer> remaining = new HashMap<>();
        jdbc.query("""
                SELECT n.node_type, count(*) FROM path_nodes n JOIN path_units u ON u.id = n.unit_id
                WHERE u.hsk_level BETWEEN ? AND ?
                  AND NOT EXISTS (SELECT 1 FROM user_path_progress p
                                  WHERE p.user_id = ? AND p.node_id = n.id AND p.completed_at IS NOT NULL)
                GROUP BY n.node_type
                """, rs -> { remaining.put(rs.getString(1), rs.getInt(2)); }, level, target, userId);
        DailyPlanner.Pace pace = DailyPlanner.pace(remaining, goal.getDailyMinutes(), goal.getTargetDate(), LocalDate.now());

        return DailyPlanResponse.builder()
                .goal(goal)
                .currentLevel(level)
                .minutesBudget(plan.minutesBudget())
                .minutesPlanned(plan.minutesPlanned())
                .items(plan.items().stream().map(item -> DailyPlanResponse.Item.builder()
                        .type(item.type()).title(item.title()).detail(item.detail()).minutes(item.minutes())
                        .link(item.link()).lessonId(item.lessonId()).build()).toList())
                .remainingLessons(pace.remainingLessons())
                .estimatedDays(pace.estimatedDays())
                .estimatedFinishDate(pace.estimatedFinishDate())
                .onTrack(pace.onTrack())
                .requiredDailyMinutes(pace.requiredDailyMinutes())
                .openMistakes(openMistakes)
                .build();
    }

    /** One level of the path with what the learner may open, and a summary of every level. */
    @Transactional(readOnly = true)
    public Roadmap.Result getRoadmap(UUID userId, Integer requestedLevel) {
        int learner = pathLevel(userId);
        int shown = requestedLevel == null ? learner : Math.max(1, Math.min(requestedLevel, MAX_COURSE_LEVEL));
        List<Attempt> attempts = loadAttempts(userId);
        return new Roadmap.Result(learner, shown, levelSummaries(userId, learner),
                layOutLevel(userId, shown, learner, attempts));
    }

    public String goalType(UUID userId) {
        return learningGoalService.get(userId).getGoalType();
    }

    /** The skills a learner most needs to practise, which their next lessons stress. */
    public List<String> urgentSkills(UUID userId) {
        return skillNeeds(loadAttempts(userId)).stream().filter(DailyPlanner.SkillNeed::urgent).map(DailyPlanner.SkillNeed::key).toList();
    }

    /** The learner's place on the path; the combined HSK 7-9 band is level 7. */
    public int pathLevel(UUID userId) {
        Integer level = userRepository.findById(userId).map(User::getHskLevel).orElse(1);
        return Math.max(1, Math.min(level == null ? 1 : level, MAX_COURSE_LEVEL));
    }

    private List<Roadmap.LevelSummary> levelSummaries(UUID userId, int learner) {
        return jdbc.query("""
                SELECT u.hsk_level, count(*), count(p.completed_at)
                FROM path_nodes n JOIN path_units u ON u.id = n.unit_id
                LEFT JOIN user_path_progress p ON p.node_id = n.id AND p.user_id = ?
                GROUP BY u.hsk_level ORDER BY u.hsk_level
                """, (rs, i) -> {
            int level = rs.getInt(1);
            int total = rs.getInt(2);
            int completed = rs.getInt(3);
            return new Roadmap.LevelSummary(level, Roadmap.levelLabel(level),
                    Roadmap.levelStatus(level, learner, completed, total), completed, total);
        }, userId);
    }

    /** Placement answers and practice answers together, so every session refines the picture. */
    private List<Attempt> loadAttempts(UUID userId) {
        List<Attempt> attempts = new ArrayList<>();
        for (SkillAttempt a : skillAttemptRepository.findByUserIdOrderByAnsweredAtAsc(userId)) {
            attempts.add(new Attempt(a.getSkillKey(), Boolean.TRUE.equals(a.getCorrect()), a.getAnsweredAt(), null));
        }
        jdbc.query("""
                SELECT q.skill_key, a.is_correct, a.attempted_at
                FROM question_attempts a JOIN question_bank_items q ON q.id = a.question_id
                WHERE a.user_id = ?
                """, rs -> {
            attempts.add(new Attempt(rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3).toLocalDateTime(), null));
        }, userId);
        jdbc.query("""
                SELECT skill_key, is_correct, answered_at, node_id FROM path_answers WHERE user_id = ?
                """, rs -> {
            long nodeId = rs.getLong(4);
            Long node = rs.wasNull() ? null : nodeId;
            attempts.add(new Attempt(rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3).toLocalDateTime(), node));
        }, userId);
        attempts.sort(Comparator.comparing(Attempt::answeredAt));
        return attempts;
    }

    static List<SkillStatResponse> buildSkillStats(List<Attempt> allAttempts, LocalDateTime now) {
        Map<String, List<Attempt>> grouped = allAttempts.stream().collect(Collectors.groupingBy(Attempt::skillKey));
        return SKILLS.stream().map(skill -> {
            List<Attempt> attempts = grouped.getOrDefault(skill.key, List.of());
            int count = attempts.size();
            List<Attempt> recent = attempts.subList(Math.max(0, count - RECENT_ATTEMPTS_WINDOW), count);
            int correct = (int) recent.stream().filter(Attempt::correct).count();
            int accuracy = recent.isEmpty() ? 0 : Math.round(correct * 100f / recent.size());
            LocalDateTime last = attempts.isEmpty() ? null : attempts.get(count - 1).answeredAt();
            long daysSince = last == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(last, now);
            String priority;
            if (count < 3) priority = "INSUFFICIENT";
            else if (accuracy < 60 || (accuracy < 80 && daysSince >= 7)) priority = "HIGH";
            else if (accuracy < 80 || daysSince >= 14) priority = "MEDIUM";
            else priority = "LOW";
            String confidence = count < 3 ? "LOW" : count < 8 ? "MEDIUM" : "HIGH";
            return SkillStatResponse.builder()
                    .skillKey(skill.key)
                    .label(skill.label)
                    .attempts(count)
                    .accuracyPercent(accuracy)
                    .priority(priority)
                    .confidence(confidence)
                    .lastPracticedAt(last)
                    .build();
        }).toList();
    }

    private List<Roadmap.Unit> layOutLevel(UUID userId, int level, int learner, List<Attempt> attempts) {
        Map<Long, List<Roadmap.NodeRef>> nodes = new HashMap<>();
        jdbc.query("""
                SELECT n.unit_id, n.id, n.title, n.node_type FROM path_nodes n JOIN path_units u ON u.id = n.unit_id
                WHERE u.hsk_level = ? ORDER BY n.unit_id, n.node_order
                """, rs -> {
            nodes.computeIfAbsent(rs.getLong(1), key -> new ArrayList<>())
                    .add(new Roadmap.NodeRef(rs.getLong(2), rs.getString(3), rs.getString(4)));
        }, level);
        List<Roadmap.UnitRef> units = jdbc.query("""
                SELECT id, unit_order, title_vi, title_zh, icon FROM path_units WHERE hsk_level = ? ORDER BY unit_order
                """, (rs, i) -> new Roadmap.UnitRef(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                nodes.getOrDefault(rs.getLong(1), List.of())), level);
        Set<Long> completed = new HashSet<>();
        Map<Long, Integer> scores = new HashMap<>();
        jdbc.query("SELECT node_id, best_score, completed_at FROM user_path_progress WHERE user_id = ?", rs -> {
            scores.put(rs.getLong(1), rs.getInt(2));
            if (rs.getTimestamp(3) != null) completed.add(rs.getLong(1));
        }, userId);
        Map<Long, Integer> wrong = attempts.stream()
                .filter(attempt -> !attempt.correct() && attempt.nodeId() != null)
                .collect(Collectors.toMap(Attempt::nodeId, attempt -> 1, Integer::sum));
        return Roadmap.layOut(units, level, learner, completed, scores, wrong);
    }

    private static List<DailyPlanner.SkillNeed> skillNeeds(List<Attempt> attempts) {
        return buildSkillStats(attempts, LocalDateTime.now()).stream()
                .filter(stat -> "HIGH".equals(stat.getPriority()) || "MEDIUM".equals(stat.getPriority()))
                .sorted(Comparator.comparing(stat -> !"HIGH".equals(stat.getPriority())))
                .map(stat -> new DailyPlanner.SkillNeed(stat.getSkillKey(), stat.getLabel(),
                        LESSON_TYPE_OF_SKILL.get(stat.getSkillKey()), "HIGH".equals(stat.getPriority())))
                .toList();
    }

    private String adaptiveSummary(PlacementResult result) {
        String confidence = switch (result.getConfidence()) {
            case "HIGH" -> "cao";
            case "MEDIUM" -> "trung bình";
            default -> "thấp";
        };
        String text = "Trình độ ước lượng của bạn là HSK " + result.getEstimatedLevel() + " (độ tin cậy " + confidence + ").";
        if (!result.getWeaknesses().isEmpty()) {
            text += " Nên củng cố trước: " + result.getWeaknesses().stream().map(this::skillLabel)
                    .collect(Collectors.joining(", ")) + ".";
        }
        return text;
    }

    private String levelSummary(BigDecimal score) {
        if (score == null) return "Chưa có kết quả đánh giá.";
        if (score.compareTo(BigDecimal.valueOf(85)) >= 0)
            return "Bạn nắm khá vững nội dung HSK 1 đã có; dữ liệu hiện tại chưa đánh giá cấp HSK 2 trở lên.";
        if (score.compareTo(BigDecimal.valueOf(60)) >= 0)
            return "Bạn đã có nền tảng HSK 1; nên củng cố các kỹ năng ưu tiên trước khi học phần mới.";
        return "Bạn nên bắt đầu từ các nội dung nền tảng HSK 1 và ôn lại theo kỹ năng ưu tiên.";
    }

    private String skillLabel(String key) {
        return SKILLS.stream().filter(skill -> skill.key.equals(key)).map(SkillDefinition::label).findFirst().orElse(key);
    }

    /** @param nodeId the path node it was answered in, if any */
    record Attempt(String skillKey, boolean correct, LocalDateTime answeredAt, Long nodeId) { }
    private record SkillDefinition(String key, String label) { }
}
