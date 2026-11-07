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
        for (String type : List.of("LESSON", "DIALOGUE", "READING", "LISTENING")) {
            for (int i = 0; i < 4; i++) all.add(new LessonCandidate(id++, type + " " + i, type, 1));
        }
        return all;
    }

    private static Input input(int minutes, int dueCards, int dueTopics, List<SkillNeed> needs) {
        return new Input(minutes, "1", dueCards, dueTopics, needs, lessons(), true);
    }

    private static List<String> types(Plan plan) {
        return plan.items().stream().map(Item::type).toList();
    }

    private static List<Item> lessonItems(Plan plan) {
        return plan.items().stream().filter(i -> i.type().equals(DailyPlanner.LESSON)).toList();
    }

    @Test
    void reviewsAndPracticeAlwaysLeaveRoomForTheNextLesson() {
        DailyPlanner.Plan plan = DailyPlanner.build(new DailyPlanner.Input(20, "1", 10, 0,
                List.of(new DailyPlanner.SkillNeed("reading", "Đọc", "READING", true)),
                List.of(new DailyPlanner.LessonCandidate(1, "Bài đọc", "READING", 1)), true, 8));
        assertTrue(plan.items().stream().anyMatch(i -> DailyPlanner.LESSON.equals(i.type())), plan.items().toString());
        assertTrue(plan.minutesPlanned() <= 20);
    }

    @Test
    void openMistakesAreAReviewBeforePracticeAndStayInsideTheReviewBudget() {
        DailyPlanner.Plan plan = DailyPlanner.build(new DailyPlanner.Input(30, "1", 0, 0, List.of(), List.of(), true, 25));
        DailyPlanner.Item item = plan.items().get(0);
        assertEquals(DailyPlanner.REVIEW_MISTAKES, item.type());
        assertEquals("/learning-path/mistakes", item.link());
        assertTrue(item.title().contains(String.valueOf(DailyPlanner.MISTAKES_PER_REVIEW)));
        assertTrue(item.minutes() <= 15);

        DailyPlanner.Plan full = DailyPlanner.build(new DailyPlanner.Input(10, "1", 100, 0, List.of(), List.of(), true, 25));
        assertTrue(full.items().stream().noneMatch(i -> DailyPlanner.REVIEW_MISTAKES.equals(i.type())), "cards used the whole review budget");
    }

    @Test
    void reviewsComeBeforePracticeAndLessons() {
        Plan plan = DailyPlanner.build(input(60, 10, 2, List.of(new SkillNeed("grammar", "Ngữ pháp", "LESSON", true))));
        assertEquals(DailyPlanner.REVIEW_CARDS, plan.items().get(0).type());
        assertEquals(DailyPlanner.PRACTICE, plan.items().get(1).type());
        assertEquals(DailyPlanner.LESSON, plan.items().get(2).type());
    }

    @Test
    void plannedTimeStaysWithinTheBudgetForEveryCommonDay() {
        for (int minutes : List.of(10, 15, 20, 30, 45, 60, 120, 240)) {
            for (int due : List.of(0, 5, 200)) {
                Plan plan = DailyPlanner.build(input(minutes, due, due, List.of(new SkillNeed("reading", "Đọc", "READING", true))));
                assertTrue(plan.minutesPlanned() <= minutes, minutes + " min, due " + due + ": " + plan.minutesPlanned());
                assertFalse(plan.items().isEmpty());
            }
        }
    }

    @Test
    void aLargeBacklogCannotTakeMoreThanHalfTheDayOfCards() {
        Plan plan = DailyPlanner.build(input(30, 500, 0, List.of()));
        Item cards = plan.items().get(0);
        assertEquals(15, cards.minutes());
        assertTrue(cards.detail().contains("470"));
        assertTrue(types(plan).contains(DailyPlanner.LESSON), "new learning must still happen");
    }

    @Test
    void lessonsFollowThePathOrderGiven() {
        Plan plan = DailyPlanner.build(input(60, 0, 0, List.of()));
        List<Item> items = lessonItems(plan);
        assertEquals("LESSON 0", items.get(0).title());
        assertEquals("LESSON 1", items.get(1).title());
    }

    @Test
    void lessonsWithWrongAnswersComeFirstAndSayWhy() {
        List<LessonCandidate> all = new ArrayList<>(lessons());
        all.add(new LessonCandidate(100, "Bài sai ít", "LESSON", 1, 1));
        all.add(new LessonCandidate(101, "Bài sai nhiều", "LESSON", 1, 3));
        Plan plan = DailyPlanner.build(new Input(30, "1", 0, 0, List.of(), all, true));
        assertEquals("Bài sai nhiều", plan.items().get(0).title());
        assertTrue(plan.items().get(0).detail().contains("sai 3 câu"));
        assertEquals("Bài sai ít", plan.items().get(1).title());
    }

    @Test
    void lessonForAWeakSkillSaysSo() {
        Plan plan = DailyPlanner.build(new Input(60, "1", 0, 0, List.of(new SkillNeed("reading", "Đọc", "READING", true)),
                List.of(new LessonCandidate(1, "Bài đọc", "READING", 1), new LessonCandidate(2, "Từ vựng", "LESSON", 1)), true));
        List<Item> items = lessonItems(plan);
        assertTrue(items.get(0).detail().contains("yếu"));
        assertFalse(items.get(1).detail().contains("yếu"));
    }

    @Test
    void learnerWithNoAnswersYetGetsAShortFirstPractice() {
        Plan plan = DailyPlanner.build(new Input(20, "1", 0, 0, List.of(), lessons(), false));
        Item first = plan.items().get(0);
        assertEquals(DailyPlanner.PRACTICE, first.type());
        assertEquals(5, first.minutes());
        assertTrue(first.title().contains("Làm quen"));
        assertEquals("/learning-path/practice?level=1&limit=5", first.link());
    }

    @Test
    void learnerWithStrongResultsGetsLessonsOnly() {
        Plan plan = DailyPlanner.build(input(20, 0, 0, List.of()));
        assertTrue(plan.items().stream().allMatch(i -> i.type().equals(DailyPlanner.LESSON)));
    }

    @Test
    void practiceRespectsTheApiSessionSize() {
        Plan tiny = DailyPlanner.build(input(5, 10, 3, List.of()));
        assertFalse(types(tiny).contains(DailyPlanner.PRACTICE), "under 5 minutes left: no 5-question session");
        Plan huge = DailyPlanner.build(input(240, 0, 100, List.of()));
        assertEquals(30, huge.items().get(0).minutes());
        assertEquals("/learning-path/practice?level=1&limit=30", huge.items().get(0).link());
    }

    @Test
    void lessonLinkOpensThatExactNodeOfThePath() {
        Plan plan = DailyPlanner.build(new Input(20, "7-9", 0, 0, List.of(),
                List.of(new LessonCandidate(958, "Bài nghe", "LISTENING", 7)), true));
        assertEquals("/learning-path/lesson/958", plan.items().get(0).link());
    }

    @Test
    void shortDayStillGetsOneLessonEvenIfItRunsOver() {
        Plan plan = DailyPlanner.build(input(5, 0, 0, List.of()));
        assertEquals(1, plan.items().size());
        assertEquals(DailyPlanner.LESSON, plan.items().get(0).type());
    }

    @Test
    void nothingLeftGivesAnEmptyPlanNotAnError() {
        Plan plan = DailyPlanner.build(new Input(20, "1", 0, 0, List.of(), List.of(), true));
        assertTrue(plan.items().isEmpty());
        assertEquals(0, plan.minutesPlanned());
    }

    @Test
    void paceComparesTheEstimateWithTheTargetDate() {
        LocalDate today = LocalDate.of(2026, 11, 6);
        // 20 vocabulary nodes = 120 minutes; 20 min/day gives 14 minutes of new learning, so 9 days.
        Map<String, Integer> left = Map.of("LESSON", 20);

        var noDate = DailyPlanner.pace(left, 20, null, today);
        assertEquals(20, noDate.remainingLessons());
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
