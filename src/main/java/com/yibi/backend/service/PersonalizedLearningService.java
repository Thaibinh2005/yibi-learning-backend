package com.yibi.backend.service;

import com.yibi.backend.dto.learningpath.*;
import com.yibi.backend.entity.*;
import com.yibi.backend.repository.*;
import lombok.RequiredArgsConstructor;
import com.yibi.backend.service.plan.DailyPlanner;
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
    private static final int RECOMMENDED_LESSONS = 3;
    private static final int MAX_COURSE_LEVEL = 7;

    /** The kind of lesson that trains each skill; passages live in READING/LISTENING lessons. */
    private static final Map<String, String> LESSON_TYPE_OF_SKILL = Map.of(
            "vocabulary", "VOCABULARY",
            "phonetics", "VOCABULARY",
            "characters", "HANZI",
            "grammar", "GRAMMAR",
            "writing", "GRAMMAR",
            "reading", "READING",
            "listening", "LISTENING",
            "speaking", "LISTENING");

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
        List<Attempt> attempts = loadAttempts(userId);
        List<SkillStatResponse> stats = buildSkillStats(attempts, LocalDateTime.now());
        int level = currentLevel(userId);
        return LearningPathOverviewResponse.builder()
                .assessmentCompleted(latest.isPresent())
                .availableHskLevel(level)
                .latestScorePercent(latest.map(PlacementAssessment::getScorePercent).orElse(null))
                .levelSummary(latest.map(a -> placementResultRepository.findById(a.getId())
                                .map(this::adaptiveSummary).orElseGet(() -> levelSummary(a.getScorePercent())))
                        .orElse("Hãy làm bài đánh giá để xác định trình độ và xây dựng lộ trình học."))
                .skills(stats)
                .recommendedLessons(recommendLessons(stats, attempts, openLessons(userId, Math.min(level, MAX_COURSE_LEVEL))))
                .goal(learningGoalService.get(userId))
                .build();
    }

    /** Sizes today's work to the learner's minutes: reviews due first, then weak skills, then new lessons. */
    @Transactional(readOnly = true)
    public DailyPlanResponse getTodayPlan(UUID userId) {
        LearningGoalResponse goal = learningGoalService.get(userId);
        int level = Math.min(currentLevel(userId), MAX_COURSE_LEVEL);
        int target = Math.max(level, Math.min(goal.getTargetLevel(), MAX_COURSE_LEVEL));
        String levelCode = level == MAX_COURSE_LEVEL ? "7-9" : String.valueOf(level);

        int dueCards = (int) srsCardRepository.countDueCards(userId, LocalDate.now());
        Integer dueTopics = jdbc.queryForObject("""
                SELECT count(*) FROM learner_skill_mastery
                WHERE user_id = ? AND level_code = ? AND next_review_at <= NOW()
                """, Integer.class, userId, levelCode);
        List<DailyPlanner.SkillNeed> needs = buildSkillStats(loadAttempts(userId), LocalDateTime.now()).stream()
                .filter(stat -> "HIGH".equals(stat.getPriority()) || "MEDIUM".equals(stat.getPriority()))
                .sorted(Comparator.comparing(stat -> !"HIGH".equals(stat.getPriority())))
                .map(stat -> new DailyPlanner.SkillNeed(stat.getSkillKey(), stat.getLabel(),
                        LESSON_TYPE_OF_SKILL.get(stat.getSkillKey()), "HIGH".equals(stat.getPriority())))
                .toList();

        DailyPlanner.Plan plan = DailyPlanner.build(new DailyPlanner.Input(goal.getDailyMinutes(), goal.getGoalType(),
                levelCode, dueCards, dueTopics == null ? 0 : dueTopics, needs, nextLessons(userId, level, target)));

        Map<String, Integer> remaining = new HashMap<>();
        jdbc.query("""
                SELECT l.content_type, count(*) FROM lessons l JOIN courses c ON c.id = l.course_id
                WHERE c.hsk_level BETWEEN ? AND ?
                  AND NOT EXISTS (SELECT 1 FROM user_progress p
                                  WHERE p.user_id = ? AND p.lesson_id = l.id AND p.status = 'COMPLETED')
                GROUP BY l.content_type
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
                .build();
    }

    private int currentLevel(UUID userId) {
        return userRepository.findById(userId).map(User::getHskLevel).orElse(1);
    }

    /** Placement answers and practice answers together, so every session refines the picture. */
    private List<Attempt> loadAttempts(UUID userId) {
        List<Attempt> attempts = new ArrayList<>();
        for (SkillAttempt a : skillAttemptRepository.findByUserIdOrderByAnsweredAtAsc(userId)) {
            attempts.add(new Attempt(a.getSkillKey(), Boolean.TRUE.equals(a.getCorrect()), a.getAnsweredAt(),
                    a.getLesson() == null ? null : a.getLesson().getId()));
        }
        jdbc.query("""
                SELECT q.skill_key, a.is_correct, a.attempted_at, q.lesson_id
                FROM question_attempts a JOIN question_bank_items q ON q.id = a.question_id
                WHERE a.user_id = ?
                """, rs -> {
            long lessonId = rs.getLong(4);
            Long lesson = rs.wasNull() ? null : lessonId;
            attempts.add(new Attempt(rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3).toLocalDateTime(), lesson));
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

    private List<LessonRow> openLessons(UUID userId, int level) {
        return jdbc.query("""
                SELECT l.id, COALESCE((l.lesson_payload ->> 'lesson_number')::int, l.lesson_order), l.title, l.content_type, c.hsk_level
                FROM lessons l JOIN courses c ON c.id = l.course_id
                WHERE c.hsk_level = ?
                  AND NOT EXISTS (SELECT 1 FROM user_progress p
                                  WHERE p.user_id = ? AND p.lesson_id = l.id AND p.status = 'COMPLETED')
                ORDER BY l.lesson_order
                """, (rs, i) -> new LessonRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getInt(5)),
                level, userId);
    }

    /** The first few unfinished lessons of each type, from the current level up to the target level. */
    private List<DailyPlanner.LessonCandidate> nextLessons(UUID userId, int level, int target) {
        return jdbc.query("""
                SELECT id, title, content_type, hsk_level FROM (
                  SELECT l.id, l.title, l.content_type, c.hsk_level, l.lesson_order,
                         row_number() OVER (PARTITION BY l.content_type ORDER BY c.hsk_level, l.lesson_order) AS rn
                  FROM lessons l JOIN courses c ON c.id = l.course_id
                  WHERE c.hsk_level BETWEEN ? AND ?
                    AND NOT EXISTS (SELECT 1 FROM user_progress p
                                    WHERE p.user_id = ? AND p.lesson_id = l.id AND p.status = 'COMPLETED')
                ) ranked WHERE rn <= 4 ORDER BY hsk_level, lesson_order
                """, (rs, i) -> new DailyPlanner.LessonCandidate(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                level, target, userId);
    }

    /**
     * Picks lessons for the weakest skills. Lessons with wrong answers come first, finished lessons never
     * appear, and each lesson type appears once so reading and listening are not crowded out.
     */
    static List<RecommendedLessonResponse> recommendLessons(List<SkillStatResponse> stats, List<Attempt> attempts,
                                                            List<LessonRow> lessons) {
        Map<String, Integer> weights = stats.stream()
                .filter(stat -> !"INSUFFICIENT".equals(stat.getPriority()))
                .collect(Collectors.toMap(SkillStatResponse::getSkillKey, stat -> switch (stat.getPriority()) {
                    case "HIGH" -> 3;
                    case "MEDIUM" -> 2;
                    default -> 1;
                }));
        if (weights.isEmpty()) return List.of();
        Map<Long, Map<String, Long>> wrongByLessonAndSkill = attempts.stream()
                .filter(attempt -> !attempt.correct() && attempt.lessonId() != null)
                .collect(Collectors.groupingBy(Attempt::lessonId,
                        Collectors.groupingBy(Attempt::skillKey, Collectors.counting())));
        List<LessonScore> scores = new ArrayList<>();
        for (LessonRow lesson : lessons) {
            int score = 0;
            List<String> matched = new ArrayList<>();
            for (SkillDefinition skill : SKILLS) {
                Integer weight = weights.get(skill.key);
                if (weight == null || !lesson.contentType().equals(LESSON_TYPE_OF_SKILL.get(skill.key))) continue;
                long wrongCount = wrongByLessonAndSkill.getOrDefault(lesson.id(), Map.of()).getOrDefault(skill.key, 0L);
                // Actual wrong answers in this lesson take precedence over a skill-only match.
                score += (int) (weight + Math.min(wrongCount, 10) * 4);
                matched.add(skill.label);
            }
            if (score > 0) scores.add(new LessonScore(lesson, score, String.join(", ", matched)));
        }
        scores.sort(Comparator.comparingInt(LessonScore::score).reversed()
                .thenComparingInt(item -> item.lesson.number()));
        Set<String> seenTypes = new HashSet<>();
        return scores.stream()
                .filter(item -> seenTypes.add(item.lesson.contentType()))
                .limit(RECOMMENDED_LESSONS)
                .map(item -> RecommendedLessonResponse.builder()
                        .lessonId(item.lesson.id())
                        .lessonNumber(item.lesson.number())
                        .title(item.lesson.title())
                        .reason("Củng cố " + item.matchedSkills + " dựa trên kết quả gần đây của bạn.")
                        .hskLevel(item.lesson.hskLevel())
                        .contentType(item.lesson.contentType())
                        .build())
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

    record Attempt(String skillKey, boolean correct, LocalDateTime answeredAt, Long lessonId) { }
    record LessonRow(long id, int number, String title, String contentType, int hskLevel) { }
    private record SkillDefinition(String key, String label) { }
    private record LessonScore(LessonRow lesson, int score, String matchedSkills) { }
}
