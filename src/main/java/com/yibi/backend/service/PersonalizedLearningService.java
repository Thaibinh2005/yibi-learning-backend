package com.yibi.backend.service;

import com.yibi.backend.dto.learningpath.*;
import com.yibi.backend.entity.*;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PersonalizedLearningService {

    private static final int QUESTIONS_PER_SKILL = 4;
    private static final List<SkillDefinition> SKILLS = List.of(
            new SkillDefinition("vocabulary", "Từ vựng"),
            new SkillDefinition("phonetics", "Pinyin và phát âm"),
            new SkillDefinition("characters", "Nhận diện chữ Hán"),
            new SkillDefinition("grammar", "Ngữ pháp")
    );

    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final UserRepository userRepository;
    private final PlacementAssessmentRepository assessmentRepository;
    private final SkillAttemptRepository skillAttemptRepository;

    @Transactional
    public PlacementTestResponse startAssessment(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        List<Lesson> lessons = courseRepository.findByHskLevelOrderByHskLevelAsc(1).stream()
                .flatMap(course -> lessonRepository.findByCourseIdOrderByLessonOrderAsc(course.getId()).stream())
                .filter(lesson -> lesson.getLessonPayload() != null)
                .toList();
        if (lessons.isEmpty()) {
            throw new BadRequestException("Chưa có dữ liệu bài học HSK 1 trong database. Hãy chạy migration và import JSON trước.");
        }

        List<Map<String, Object>> internalQuestions = generateQuestions(lessons);
        if (internalQuestions.size() < 4) {
            throw new BadRequestException("Dữ liệu HSK 1 chưa đủ câu hỏi có đáp án để tạo bài kiểm tra.");
        }

        PlacementAssessment assessment = assessmentRepository.save(PlacementAssessment.builder()
                .user(user)
                .hskLevel(1)
                .questionSnapshot(Map.of("schema_version", 1, "questions", internalQuestions))
                .totalQuestions(internalQuestions.size())
                .build());

        return PlacementTestResponse.builder()
                .assessmentId(assessment.getId())
                .hskLevel(1)
                .notice("Bài đánh giá chỉ sử dụng dữ liệu HSK 1 hiện có; kết quả chưa thể xác định trình độ HSK 2 trở lên.")
                .questions(internalQuestions.stream().map(this::toPublicQuestion).toList())
                .build();
    }

    @Transactional
    public AssessmentResultResponse submitAssessment(UUID userId, Long assessmentId, AssessmentSubmitRequest request) {
        PlacementAssessment assessment = assessmentRepository.findByIdAndUserId(assessmentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Placement assessment", "id", assessmentId));
        if (assessment.getFinishedAt() != null) {
            throw new BadRequestException("Bài kiểm tra này đã được nộp.");
        }

        Object rawQuestions = assessment.getQuestionSnapshot().get("questions");
        if (!(rawQuestions instanceof List<?> questionList)) {
            throw new BadRequestException("Dữ liệu câu hỏi của bài kiểm tra không hợp lệ.");
        }
        Map<String, AssessmentAnswerRequest> submitted = new HashMap<>();
        for (AssessmentAnswerRequest answer : request.getAnswers()) {
            if (submitted.put(answer.getQuestionId(), answer) != null) {
                throw new BadRequestException("Có câu trả lời bị gửi lặp.");
            }
        }

        Set<String> validQuestionIds = questionList.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(question -> String.valueOf(question.get("id")))
                .collect(Collectors.toSet());
        if (!validQuestionIds.containsAll(submitted.keySet())) {
            throw new BadRequestException("Bài nộp có mã câu hỏi không thuộc bài kiểm tra này.");
        }

        int correctCount = 0;
        List<SkillAttempt> attempts = new ArrayList<>();
        for (Object rawQuestion : questionList) {
            if (!(rawQuestion instanceof Map<?, ?> question)) continue;
            String questionId = String.valueOf(question.get("id"));
            AssessmentAnswerRequest answer = submitted.get(questionId);
            String submittedValue = answer != null && answer.getAnswer() != null ? answer.getAnswer().trim() : "";
            String correctAnswer = String.valueOf(question.get("correctAnswer"));
            boolean correct = !submittedValue.isBlank() && submittedValue.equalsIgnoreCase(correctAnswer.trim());
            if (correct) correctCount++;

            Long lessonId = asLong(question.get("lessonId"));
            Lesson lesson = lessonId != null ? lessonRepository.findById(lessonId).orElse(null) : null;
            attempts.add(SkillAttempt.builder()
                    .assessment(assessment)
                    .user(assessment.getUser())
                    .questionKey(questionId)
                    .skillKey(String.valueOf(question.get("skillKey")))
                    .topicKey(String.valueOf(question.get("topic")))
                    .lesson(lesson)
                    .correct(correct)
                    .responseTimeSeconds(answer != null && answer.getResponseTimeSeconds() != null
                            ? answer.getResponseTimeSeconds() : 0)
                    .answeredAt(LocalDateTime.now())
                    .build());
        }

        BigDecimal score = BigDecimal.valueOf(correctCount * 100.0 / questionList.size())
                .setScale(2, RoundingMode.HALF_UP);
        assessment.setCorrectCount(correctCount);
        assessment.setScorePercent(score);
        assessment.setFinishedAt(LocalDateTime.now());
        assessmentRepository.save(assessment);
        skillAttemptRepository.saveAll(attempts);
        skillAttemptRepository.flush();

        return AssessmentResultResponse.builder()
                .assessmentId(assessmentId)
                .correctCount(correctCount)
                .totalQuestions(questionList.size())
                .scorePercent(score)
                .levelSummary(levelSummary(score))
                .skills(buildSkillStats(skillAttemptRepository.findByUserIdOrderByAnsweredAtAsc(userId)))
                .build();
    }

    @Transactional(readOnly = true)
    public LearningPathOverviewResponse getOverview(UUID userId) {
        List<SkillAttempt> attempts = skillAttemptRepository.findByUserIdOrderByAnsweredAtAsc(userId);
        Optional<PlacementAssessment> latest = assessmentRepository
                .findFirstByUserIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(userId);
        List<SkillStatResponse> stats = buildSkillStats(attempts);
        return LearningPathOverviewResponse.builder()
                .assessmentCompleted(latest.isPresent())
                .availableHskLevel(1)
                .latestScorePercent(latest.map(PlacementAssessment::getScorePercent).orElse(null))
                .levelSummary(latest.map(a -> levelSummary(a.getScorePercent()))
                        .orElse("Hãy làm bài đánh giá để bắt đầu xây dựng lộ trình HSK 1."))
                .skills(stats)
                .recommendedLessons(recommendLessons(stats, attempts))
                .build();
    }

    private List<Map<String, Object>> generateQuestions(List<Lesson> lessons) {
        Map<String, WordSource> uniqueWords = new LinkedHashMap<>();
        List<GrammarSource> grammars = new ArrayList<>();
        for (Lesson lesson : lessons) {
            Map<String, Object> sections = asMap(lesson.getLessonPayload().get("sections"));
            int lessonNumber = Optional.ofNullable(asLong(lesson.getLessonPayload().get("lesson_number")))
                    .map(Long::intValue).orElse(lesson.getLessonOrder());
            for (Object rawWord : asList(sections.get("vocabulary"))) {
                Map<String, Object> word = asMap(rawWord);
                String hanzi = string(word.get("hanzi"));
                String pinyin = string(word.get("pinyin"));
                String meaning = string(word.get("meaning"));
                if (hanzi.isBlank() || pinyin.isBlank() || meaning.isBlank()) continue;
                uniqueWords.putIfAbsent(hanzi + "|" + pinyin + "|" + meaning,
                        new WordSource(hanzi, pinyin, meaning, lessonNumber, lesson.getId()));
            }
            for (Object rawGrammar : asList(sections.get("grammar"))) {
                Map<String, Object> grammar = asMap(rawGrammar);
                String structure = string(grammar.get("structure"));
                String explanation = string(grammar.get("explanation"));
                if (!structure.isBlank() && !explanation.isBlank()) {
                    grammars.add(new GrammarSource(structure, explanation, lessonNumber, lesson.getId()));
                }
            }
        }

        List<WordSource> words = new ArrayList<>(uniqueWords.values());
        Map<String, List<Map<String, Object>>> pools = new LinkedHashMap<>();
        for (String skill : List.of("vocabulary", "phonetics", "characters", "grammar")) pools.put(skill, new ArrayList<>());

        for (WordSource word : words) {
            addChoiceQuestion(pools.get("vocabulary"), "vocabulary", "meaning", word.lessonNumber, word.lessonId,
                    "Từ “" + word.hanzi + "” có nghĩa là gì?", word.meaning,
                    words.stream().map(WordSource::meaning).toList(), 4);
            addChoiceQuestion(pools.get("phonetics"), "phonetics", "pinyin", word.lessonNumber, word.lessonId,
                    "Pinyin đúng của “" + word.hanzi + "” là gì?", word.pinyin,
                    words.stream().map(WordSource::pinyin).toList(), 4);
            addChoiceQuestion(pools.get("characters"), "characters", "hanzi", word.lessonNumber, word.lessonId,
                    "Chữ Hán nào có nghĩa “" + word.meaning + "”?", word.hanzi,
                    words.stream().map(WordSource::hanzi).toList(), 4);
        }

        List<String> grammarChoices = grammars.stream().map(GrammarSource::structure).distinct().toList();
        if (grammarChoices.size() >= 4) {
            for (GrammarSource grammar : grammars) {
                addChoiceQuestion(pools.get("grammar"), "grammar", grammar.structure,
                        grammar.lessonNumber, grammar.lessonId,
                        "Cấu trúc nào phù hợp với cách dùng sau: " + grammar.explanation,
                        grammar.structure, grammarChoices, 4);
            }
        }

        Random random = new Random();
        List<Map<String, Object>> selected = new ArrayList<>();
        for (List<Map<String, Object>> pool : pools.values()) {
            Collections.shuffle(pool, random);
            selected.addAll(pool.stream().limit(QUESTIONS_PER_SKILL).toList());
        }
        Collections.shuffle(selected, random);
        return selected;
    }

    private void addChoiceQuestion(List<Map<String, Object>> target, String skill, String topic,
                                   Integer lessonNumber, Long lessonId, String prompt, String answer,
                                   List<String> choicePool, int optionCount) {
        List<String> distractors = choicePool.stream()
                .filter(value -> value != null && !value.isBlank() && !value.equals(answer))
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        if (distractors.size() < optionCount - 1) return;
        Collections.shuffle(distractors);
        List<String> options = new ArrayList<>(distractors.subList(0, optionCount - 1));
        options.add(answer);
        Collections.shuffle(options);
        String id = UUID.randomUUID().toString();
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("id", id);
        question.put("skillKey", skill);
        question.put("topic", topic);
        question.put("lessonNumber", lessonNumber);
        question.put("lessonId", lessonId);
        question.put("prompt", prompt);
        question.put("options", options);
        question.put("correctAnswer", answer);
        target.add(question);
    }

    private PlacementQuestionResponse toPublicQuestion(Map<String, Object> question) {
        Object rawOptions = question.get("options");
        List<String> options = rawOptions instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        String skill = string(question.get("skillKey"));
        return PlacementQuestionResponse.builder()
                .id(string(question.get("id")))
                .skillKey(skill)
                .skillLabel(skillLabel(skill))
                .topic(string(question.get("topic")))
                .lessonNumber(Optional.ofNullable(asLong(question.get("lessonNumber"))).map(Long::intValue).orElse(null))
                .prompt(string(question.get("prompt")))
                .options(options)
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
                                                               List<SkillAttempt> attempts) {
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
        for (Course course : courseRepository.findByHskLevelOrderByHskLevelAsc(1)) {
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

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private record SkillDefinition(String key, String label) { }
    private record WordSource(String hanzi, String pinyin, String meaning, Integer lessonNumber, Long lessonId) { }
    private record GrammarSource(String structure, String explanation, Integer lessonNumber, Long lessonId) { }
    private record LessonScore(Lesson lesson, Integer lessonNumber, int score, String matchedSkills) { }
}
