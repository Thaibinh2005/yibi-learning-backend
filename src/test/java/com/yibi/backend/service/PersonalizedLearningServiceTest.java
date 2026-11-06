package com.yibi.backend.service;

import com.yibi.backend.dto.learningpath.RecommendedLessonResponse;
import com.yibi.backend.dto.learningpath.SkillStatResponse;
import com.yibi.backend.service.PersonalizedLearningService.Attempt;
import com.yibi.backend.service.PersonalizedLearningService.LessonRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersonalizedLearningServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 12, 0);

    private static List<Attempt> attempts(String skill, int wrong, int right) {
        List<Attempt> list = new ArrayList<>();
        for (int i = 0; i < wrong; i++) list.add(new Attempt(skill, false, NOW.minusDays(1).plusSeconds(i), null));
        for (int i = 0; i < right; i++) list.add(new Attempt(skill, true, NOW.minusHours(1).plusSeconds(i), null));
        return list;
    }

    private static SkillStatResponse stat(List<Attempt> attempts, String skill) {
        return PersonalizedLearningService.buildSkillStats(attempts, NOW).stream()
                .filter(s -> s.getSkillKey().equals(skill)).findFirst().orElseThrow();
    }

    @Test
    void recentAnswersDriveAccuracySoImprovementChangesThePriority() {
        List<Attempt> before = attempts("grammar", 10, 0);
        assertEquals("HIGH", stat(before, "grammar").getPriority());

        List<Attempt> after = attempts("grammar", 10, PersonalizedLearningService.RECENT_ATTEMPTS_WINDOW);
        SkillStatResponse improved = stat(after, "grammar");
        assertEquals(100, improved.getAccuracyPercent());
        assertEquals("LOW", improved.getPriority());
        assertEquals(40, improved.getAttempts());
    }

    @Test
    void fewerThanThreeAnswersIsNotEnoughToJudge() {
        assertEquals("INSUFFICIENT", stat(attempts("reading", 2, 0), "reading").getPriority());
    }

    private static List<LessonRow> levelOneLessons() {
        return List.of(
                new LessonRow(1, 1, "Từ vựng 1", "VOCABULARY", 1),
                new LessonRow(2, 2, "Từ vựng 2", "VOCABULARY", 1),
                new LessonRow(3, 3, "Bài đọc 1", "READING", 1),
                new LessonRow(4, 4, "Bài nghe 1", "LISTENING", 1),
                new LessonRow(5, 5, "Ngữ pháp 1", "GRAMMAR", 1));
    }

    @Test
    void readingAndListeningLessonsCanBeRecommended() {
        List<Attempt> all = new ArrayList<>(attempts("reading", 5, 0));
        all.addAll(attempts("listening", 5, 0));
        List<RecommendedLessonResponse> picks = PersonalizedLearningService.recommendLessons(
                PersonalizedLearningService.buildSkillStats(all, NOW), all, levelOneLessons());
        assertEquals(List.of("READING", "LISTENING"), picks.stream().map(RecommendedLessonResponse::getContentType).toList());
        assertEquals(1, picks.get(0).getHskLevel());
    }

    @Test
    void wrongAnswersInALessonPutThatLessonFirstAndTypesDoNotRepeat() {
        List<Attempt> all = new ArrayList<>(attempts("vocabulary", 3, 0));
        all.add(new Attempt("vocabulary", false, NOW.minusHours(2), 2L));
        List<RecommendedLessonResponse> picks = PersonalizedLearningService.recommendLessons(
                PersonalizedLearningService.buildSkillStats(all, NOW), all, levelOneLessons());
        assertEquals(List.of(2L), picks.stream().map(RecommendedLessonResponse::getLessonId).toList());
    }

    @Test
    void noMeasuredSkillMeansNoRecommendation() {
        assertTrue(PersonalizedLearningService.recommendLessons(
                PersonalizedLearningService.buildSkillStats(List.of(), NOW), List.of(), levelOneLessons()).isEmpty());
    }
}
