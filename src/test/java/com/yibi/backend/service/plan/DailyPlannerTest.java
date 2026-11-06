package com.yibi.backend.service.plan;

import com.yibi.backend.service.plan.DailyPlanner.Input;
import com.yibi.backend.service.plan.DailyPlanner.Item;
import com.yibi.backend.service.plan.DailyPlanner.LessonCandidate;
import com.yibi.backend.service.plan.DailyPlanner.Plan;
import com.yibi.backend.service.plan.DailyPlanner.SkillNeed;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DailyPlannerTest {

    private static List<LessonCandidate> lessons() {
        List<LessonCandidate> all = new ArrayList<>();
        long id = 1;
        for (String type : List.of("VOCABULARY", "GRAMMAR", "HANZI", "READING", "LISTENING")) {
            for (int i = 0; i < 4; i++) all.add(new LessonCandidate(id++, type + " " + i, type, 1));
        }
        return all;
    }

    private static Input input(int minutes, String goal, int dueCards, int dueTopics, List<SkillNeed> needs) {
        return new Input(minutes, goal, "1", dueCards, dueTopics, needs, lessons());
    }

    private static List<String> types(Plan plan) {
        return plan.items().stream().map(Item::type).toList();
    }

    @Test
    void reviewsComeBeforePracticeAndLessons() {
        Plan plan = DailyPlanner.build(input(60, "HSK", 10, 2, List.of(new SkillNeed("grammar", "Ngữ pháp", "GRAMMAR", true))));
        assertEquals(DailyPlanner.REVIEW_CARDS, plan.items().get(0).type());
        assertEquals(DailyPlanner.PRACTICE, plan.items().get(1).type());
        assertEquals(DailyPlanner.LESSON, plan.items().get(2).type());
    }

    @Test
    void plannedTimeStaysWithinTheBudgetForEveryCommonDay() {
        for (int minutes : List.of(10, 15, 20, 30, 45, 60, 120, 240)) {
            for (int due : List.of(0, 5, 200)) {
                Plan plan = DailyPlanner.build(input(minutes, "HSK", due, due, List.of(new SkillNeed("reading", "Đọc", "READING", true))));
                assertTrue(plan.minutesPlanned() <= minutes, minutes + " min, due " + due + ": " + plan.minutesPlanned());
                assertFalse(plan.items().isEmpty());
            }
        }
    }

    @Test
    void aLargeBacklogCannotTakeMoreThanHalfTheDayOfCards() {
        Plan plan = DailyPlanner.build(input(30, "HSK", 500, 0, List.of()));
        Item cards = plan.items().get(0);
        assertEquals(15, cards.minutes());
        assertTrue(cards.detail().contains("470"));
        assertTrue(types(plan).contains(DailyPlanner.LESSON), "new learning must still happen");
    }

    @Test
    void weakSkillLessonsComeFirstThenTheGoalOrder() {
        Plan plan = DailyPlanner.build(input(60, "HSK", 0, 0, List.of(new SkillNeed("reading", "Đọc", "READING", true))));
        List<Item> lessonItems = plan.items().stream().filter(i -> i.type().equals(DailyPlanner.LESSON)).toList();
        assertTrue(lessonItems.get(0).title().startsWith("READING"));
        assertTrue(lessonItems.get(0).detail().contains("yếu"));
        assertTrue(lessonItems.get(1).title().startsWith("VOCABULARY"));
    }

    @Test
    void goalChangesWhichLessonsComeFirst() {
        assertTrue(firstLesson(input(30, "HSK", 0, 0, List.of())).startsWith("VOCABULARY"));
        assertTrue(firstLesson(input(30, "HSKK", 0, 0, List.of())).startsWith("LISTENING"));
        assertTrue(firstLesson(input(30, "COMMUNICATION", 0, 0, List.of())).startsWith("LISTENING"));
    }

    @Test
    void whenSeveralSkillsAreWeakTheGoalDecidesTheOrder() {
        List<SkillNeed> allWeak = List.of(new SkillNeed("vocabulary", "Từ vựng", "VOCABULARY", true),
                new SkillNeed("listening", "Nghe", "LISTENING", true));
        assertTrue(firstLesson(input(30, "HSK", 0, 0, allWeak)).startsWith("VOCABULARY"));
        assertTrue(firstLesson(input(30, "HSKK", 0, 0, allWeak)).startsWith("LISTENING"));
    }

    @Test
    void urgentSkillBeatsTheGoalPreference() {
        List<SkillNeed> needs = List.of(new SkillNeed("grammar", "Ngữ pháp", "GRAMMAR", true),
                new SkillNeed("listening", "Nghe", "LISTENING", false));
        assertTrue(firstLesson(input(30, "HSKK", 0, 0, needs)).startsWith("GRAMMAR"));
    }

    private static String firstLesson(Input in) {
        return DailyPlanner.build(in).items().stream().filter(i -> i.type().equals(DailyPlanner.LESSON))
                .findFirst().orElseThrow().title();
    }

    @Test
    void newLearnerWithNoDataGetsLessonsOnly() {
        Plan plan = DailyPlanner.build(input(20, "HSK", 0, 0, List.of()));
        assertTrue(plan.items().stream().allMatch(i -> i.type().equals(DailyPlanner.LESSON)));
    }

    @Test
    void practiceRespectsTheApiSessionSize() {
        Plan tiny = DailyPlanner.build(input(5, "HSK", 10, 3, List.of()));
        assertFalse(types(tiny).contains(DailyPlanner.PRACTICE), "under 5 minutes left: no 5-question session");
        Plan huge = DailyPlanner.build(input(240, "HSK", 0, 100, List.of()));
        assertEquals(30, huge.items().get(0).minutes());
        assertTrue(huge.items().get(0).link().contains("limit=30"));
    }

    @Test
    void lessonLinkOpensThatExactLessonAtItsLevel() {
        Plan plan = DailyPlanner.build(new Input(20, "HSK", "7-9", 0, 0, List.of(),
                List.of(new LessonCandidate(958, "Bài nghe", "LISTENING", 7))));
        assertEquals("/courses?hskLevel=7&lessonId=958", plan.items().get(0).link());
    }

    @Test
    void shortDayStillGetsOneLessonEvenIfItRunsOver() {
        Plan plan = DailyPlanner.build(input(5, "HSK", 0, 0, List.of()));
        assertEquals(1, plan.items().size());
        assertEquals(DailyPlanner.LESSON, plan.items().get(0).type());
    }

    @Test
    void nothingLeftGivesAnEmptyPlanNotAnError() {
        Plan plan = DailyPlanner.build(new Input(20, "HSK", "1", 0, 0, List.of(), List.of()));
        assertTrue(plan.items().isEmpty());
        assertEquals(0, plan.minutesPlanned());
    }

    @Test
    void paceComparesTheEstimateWithTheTargetDate() {
        LocalDate today = LocalDate.of(2026, 11, 6);
        // 10 vocabulary lessons = 120 minutes; 20 min/day gives 14 minutes of new learning, so 9 days.
        Map<String, Integer> left = Map.of("VOCABULARY", 10);

        var noDate = DailyPlanner.pace(left, 20, null, today);
        assertEquals(10, noDate.remainingLessons());
        assertEquals(9, noDate.estimatedDays());
        assertEquals(LocalDate.of(2026, 11, 15), noDate.estimatedFinishDate());
        assertNull(noDate.onTrack());
        assertNull(noDate.requiredDailyMinutes());

        assertTrue(DailyPlanner.pace(left, 20, today.plusDays(9), today).onTrack());

        var tooSoon = DailyPlanner.pace(left, 20, today.plusDays(3), today);
        assertFalse(tooSoon.onTrack());
        assertEquals(58, tooSoon.requiredDailyMinutes()); // 120 / 0.7 / 3

        var today0 = DailyPlanner.pace(left, 20, today, today);
        assertFalse(today0.onTrack());
        assertNull(today0.requiredDailyMinutes());
    }

    @Test
    void everythingFinishedIsOnTrack() {
        LocalDate today = LocalDate.of(2026, 11, 6);
        var done = DailyPlanner.pace(Map.of(), 20, today.plusDays(1), today);
        assertEquals(0, done.remainingLessons());
        assertEquals(0, done.estimatedDays());
        assertTrue(done.onTrack());
    }
}
