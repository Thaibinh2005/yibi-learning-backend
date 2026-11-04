package com.yibi.backend.service;

import com.yibi.backend.dto.learningpath.*;
import com.yibi.backend.entity.*;
import com.yibi.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final PlacementAssessmentRepository assessmentRepository;
    private final SkillAttemptRepository skillAttemptRepository;
    private final UserRepository userRepository;
    private final PlacementResultRepository placementResultRepository;

    @Transactional(readOnly = true)
    public LearningPathOverviewResponse getOverview(UUID userId) {
        List<SkillAttempt> attempts = skillAttemptRepository.findByUserIdOrderByAnsweredAtAsc(userId);
        Optional<PlacementAssessment> latest = assessmentRepository
                .findFirstByUserIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(userId);
        List<SkillStatResponse> stats = buildSkillStats(attempts);
        int level = userRepository.findById(userId).map(User::getHskLevel).orElse(1);
        return LearningPathOverviewResponse.builder()
                .assessmentCompleted(latest.isPresent())
                .availableHskLevel(level)
                .latestScorePercent(latest.map(PlacementAssessment::getScorePercent).orElse(null))
                .levelSummary(latest.map(a -> placementResultRepository.findById(a.getId())
                                .map(this::adaptiveSummary).orElseGet(() -> levelSummary(a.getScorePercent())))
                        .orElse("Hãy làm bài đánh giá để xác định trình độ và xây dựng lộ trình học."))
                .skills(stats)
                .recommendedLessons(recommendLessons(stats, attempts, level))
                .build();
    }

    private List<SkillStatResponse> buildSkillStats(List<SkillAttempt> allAttempts) {
        Map<String, List<SkillAttempt>> grouped = allAttempts.stream()
                .collect(Collectors.groupingBy(SkillAttempt::getSkillKey));
        LocalDateTime now = LocalDateTime.now();
        return SKILLS.stream().map(skill -> {
            List<SkillAttempt> attempts = grouped.getOrDefault(skill.key, List.of());
            int count = attempts.size();
            int correct = (int) attempts.stream().filter(SkillAttempt::getCorrect).count();
            int accuracy = count == 0 ? 0 : Math.round(correct * 100f / count);
            LocalDateTime last = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1).getAnsweredAt();
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

    private List<RecommendedLessonResponse> recommendLessons(List<SkillStatResponse> stats,
                                                               List<SkillAttempt> attempts, int level) {
        Map<String, Integer> weights = stats.stream()
                .filter(stat -> !"INSUFFICIENT".equals(stat.getPriority()))
                .collect(Collectors.toMap(SkillStatResponse::getSkillKey, stat -> switch (stat.getPriority()) {
                    case "HIGH" -> 3;
                    case "MEDIUM" -> 2;
                    default -> 1;
                }));
        if (weights.isEmpty()) return List.of();
        Map<Long, Map<String, Long>> wrongByLessonAndSkill = attempts.stream()
                .filter(attempt -> !Boolean.TRUE.equals(attempt.getCorrect()) && attempt.getLesson() != null)
                .collect(Collectors.groupingBy(attempt -> attempt.getLesson().getId(),
                        Collectors.groupingBy(SkillAttempt::getSkillKey, Collectors.counting())));
        List<LessonScore> scores = new ArrayList<>();
        for (Course course : courseRepository.findByHskLevelOrderByHskLevelAsc(level)) {
            for (Lesson lesson : lessonRepository.findByCourseIdOrderByLessonOrderAsc(course.getId())) {
                if (lesson.getLessonPayload() == null) continue;
                Map<String, Object> sections = asMap(lesson.getLessonPayload().get("sections"));
                int score = 0;
                List<String> matched = new ArrayList<>();
                for (Map.Entry<String, Integer> weight : weights.entrySet()) {
                    if (!asList(sections.get(weight.getKey())).isEmpty()) {
                        long wrongCount = wrongByLessonAndSkill
                                .getOrDefault(lesson.getId(), Map.of())
                                .getOrDefault(weight.getKey(), 0L);
                        // Actual wrong answers in this lesson take precedence over a skill-only match.
                        score += (int) (weight.getValue() + Math.min(wrongCount, 10) * 4);
                        matched.add(skillLabel(weight.getKey()));
                    }
                }
                if (score > 0) {
                    int lessonNumber = Optional.ofNullable(asLong(lesson.getLessonPayload().get("lesson_number")))
                            .map(Long::intValue).orElse(lesson.getLessonOrder());
                    scores.add(new LessonScore(lesson, lessonNumber, score, String.join(", ", matched)));
                }
            }
        }
        scores.sort(Comparator.comparingInt(LessonScore::score).reversed()
                .thenComparingInt(LessonScore::lessonNumber));
        return scores.stream().limit(3).map(item -> RecommendedLessonResponse.builder()
                .lessonId(item.lesson.getId())
                .lessonNumber(item.lessonNumber)
                .title(item.lesson.getTitle())
                .reason("Ôn lại phần " + item.matchedSkills + " dựa trên các câu bạn cần củng cố.")
                .build()).toList();
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

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private record SkillDefinition(String key, String label) { }
    private record LessonScore(Lesson lesson, Integer lessonNumber, int score, String matchedSkills) { }
}
