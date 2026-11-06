package com.yibi.backend.service.plan;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a learner's state into today's work. It has no database access so every rule can be tested directly.
 *
 * <p>Order: flashcards due, then adaptive practice for reviews and weak skills, then new lessons in the
 * remaining time. Reviews may use at most half the time, so a large backlog cannot stop new learning.
 */
public final class DailyPlanner {

    public static final String REVIEW_CARDS = "REVIEW_CARDS";
    public static final String PRACTICE = "PRACTICE";
    public static final String LESSON = "LESSON";

    private static final double MINUTES_PER_CARD = 0.5;
    private static final double REVIEW_SHARE = 0.5;
    private static final double PRACTICE_SHARE = 0.3;
    /** The practice API runs sessions of 5 to 30 questions, at about a minute each. */
    private static final int MIN_PRACTICE_QUESTIONS = 5;
    private static final int MAX_PRACTICE_QUESTIONS = 30;
    private static final int QUESTIONS_PER_DUE_TOPIC = 2;
    private static final int MAX_LESSONS = 4;
    /** Part of each day goes to reviews, so only this share of it moves through new lessons. */
    private static final double NEW_LEARNING_SHARE = 0.7;
    private static final int DEFAULT_LESSON_MINUTES = 10;

    private static final Map<String, Integer> LESSON_MINUTES = Map.of(
            "VOCABULARY", 12, "GRAMMAR", 10, "HANZI", 8, "READING", 8, "LISTENING", 8);

    private static final Map<String, List<String>> LESSON_ORDER_BY_GOAL = Map.of(
            "HSK", List.of("VOCABULARY", "GRAMMAR", "READING", "LISTENING", "HANZI"),
            "HSKK", List.of("LISTENING", "VOCABULARY", "READING", "GRAMMAR", "HANZI"),
            "COMMUNICATION", List.of("LISTENING", "VOCABULARY", "GRAMMAR", "READING", "HANZI"));

    private DailyPlanner() { }

    /** A skill to reinforce; urgent ones (high priority) get their lessons before the others. */
    public record SkillNeed(String key, String label, String lessonType, boolean urgent) { }

    public record LessonCandidate(long id, String title, String contentType, int hskLevel) { }

    public record Input(int minutes, String goalType, String levelCode, int dueCards, int dueTopics,
                        List<SkillNeed> needs, List<LessonCandidate> lessons) { }

    public record Item(String type, String title, String detail, int minutes, String link, Long lessonId) { }

    public record Plan(int minutesBudget, int minutesPlanned, List<Item> items) { }

    public record Pace(int remainingLessons, int estimatedDays, LocalDate estimatedFinishDate,
                       Boolean onTrack, Integer requiredDailyMinutes) { }

    public static Plan build(Input in) {
        List<Item> items = new ArrayList<>();
        int remaining = in.minutes();
        int reviewBudget = (int) Math.ceil(in.minutes() * REVIEW_SHARE);

        int cards = Math.min(in.dueCards(), (int) (reviewBudget / MINUTES_PER_CARD));
        if (cards > 0) {
            int minutes = (int) Math.ceil(cards * MINUTES_PER_CARD);
            String detail = cards < in.dueCards()
                    ? "Còn " + (in.dueCards() - cards) + " thẻ đến hạn cho các ngày sau."
                    : "Các thẻ từ vựng đã đến hạn theo lịch lặp lại ngắt quãng.";
            items.add(new Item(REVIEW_CARDS, "Ôn " + cards + " thẻ từ vựng", detail, minutes, "/flashcards", null));
            remaining -= minutes;
        }

        int questions = practiceQuestions(in, remaining, reviewBudget - (in.minutes() - remaining));
        if (questions > 0) {
            items.add(new Item(PRACTICE, "Luyện " + questions + " câu thích ứng", practiceDetail(in), questions,
                    "/practice/adaptive?level=" + in.levelCode() + "&limit=" + questions, null));
            remaining -= questions;
        }

        List<LessonCandidate> ordered = orderLessons(in);
        int chosen = 0;
        for (LessonCandidate lesson : ordered) {
            int minutes = lessonMinutes(lesson.contentType());
            if (chosen >= MAX_LESSONS || minutes > remaining) break;
            items.add(lessonItem(lesson, minutes, in));
            remaining -= minutes;
            chosen++;
        }

        // A very short day still gets one concrete thing to do.
        if (items.isEmpty() && !ordered.isEmpty()) {
            LessonCandidate first = ordered.get(0);
            items.add(lessonItem(first, lessonMinutes(first.contentType()), in));
        }
        int planned = items.stream().mapToInt(Item::minutes).sum();
        return new Plan(in.minutes(), planned, items);
    }

    private static int practiceQuestions(Input in, int remaining, int reviewBudgetLeft) {
        if (in.dueTopics() == 0 && in.needs().isEmpty()) return 0;
        int cap = Math.max(MIN_PRACTICE_QUESTIONS, (int) Math.ceil(in.minutes() * PRACTICE_SHARE));
        if (in.dueTopics() > 0) {
            cap = Math.max(cap, Math.min(in.dueTopics() * QUESTIONS_PER_DUE_TOPIC, reviewBudgetLeft));
        }
        int questions = Math.min(Math.min(cap, remaining), MAX_PRACTICE_QUESTIONS);
        return questions >= MIN_PRACTICE_QUESTIONS ? questions : 0;
    }

    private static String practiceDetail(Input in) {
        List<String> reasons = new ArrayList<>();
        if (in.dueTopics() > 0) reasons.add(in.dueTopics() + " chủ đề đến hạn ôn");
        if (!in.needs().isEmpty()) {
            reasons.add("kỹ năng cần củng cố: " + String.join(", ", in.needs().stream().map(SkillNeed::label).toList()));
        }
        return "Ưu tiên " + String.join("; ", reasons) + ".";
    }

    /** One lesson per type in turn, types for weak skills first, in the order that suits the goal. */
    private static List<LessonCandidate> orderLessons(Input in) {
        List<String> goalOrder = LESSON_ORDER_BY_GOAL.getOrDefault(in.goalType(), LESSON_ORDER_BY_GOAL.get("HSK"));
        Set<String> typeOrder = new LinkedHashSet<>();
        // Urgent skills first, then other weak skills; within each group the goal decides, then the rest follow.
        for (boolean urgent : List.of(true, false)) {
            for (String type : goalOrder) {
                boolean needed = in.needs().stream().anyMatch(need -> need.urgent() == urgent && type.equals(need.lessonType()));
                if (needed) typeOrder.add(type);
            }
        }
        typeOrder.addAll(goalOrder);

        Map<String, List<LessonCandidate>> byType = new LinkedHashMap<>();
        for (String type : typeOrder) byType.put(type, new ArrayList<>());
        for (LessonCandidate lesson : in.lessons()) {
            byType.computeIfAbsent(lesson.contentType(), key -> new ArrayList<>()).add(lesson);
        }
        List<LessonCandidate> ordered = new ArrayList<>();
        for (int round = 0; ordered.size() < in.lessons().size(); round++) {
            for (List<LessonCandidate> lessons : byType.values()) {
                if (round < lessons.size()) ordered.add(lessons.get(round));
            }
        }
        return ordered;
    }

    private static Item lessonItem(LessonCandidate lesson, int minutes, Input in) {
        boolean weak = in.needs().stream().anyMatch(need -> lesson.contentType().equals(need.lessonType()));
        String detail = weak ? "Bài này giúp củng cố kỹ năng đang yếu." : "Bài tiếp theo trong lộ trình của bạn.";
        return new Item(LESSON, lesson.title(), detail, minutes,
                "/courses?hskLevel=" + lesson.hskLevel() + "&lessonId=" + lesson.id(), lesson.id());
    }

    private static int lessonMinutes(String contentType) {
        return LESSON_MINUTES.getOrDefault(contentType, DEFAULT_LESSON_MINUTES);
    }

    /** How long the remaining lessons take at the learner's pace, and whether that meets their date. */
    public static Pace pace(Map<String, Integer> remainingByType, int dailyMinutes, LocalDate targetDate, LocalDate today) {
        int lessons = remainingByType.values().stream().mapToInt(Integer::intValue).sum();
        int minutes = remainingByType.entrySet().stream()
                .mapToInt(entry -> entry.getValue() * lessonMinutes(entry.getKey())).sum();
        double newLearningMinutes = dailyMinutes * NEW_LEARNING_SHARE;
        int days = (int) Math.ceil(minutes / newLearningMinutes);
        LocalDate finish = today.plusDays(days);

        Boolean onTrack = null;
        Integer required = null;
        if (targetDate != null) {
            onTrack = !finish.isAfter(targetDate);
            long daysLeft = ChronoUnit.DAYS.between(today, targetDate);
            if (daysLeft > 0) required = (int) Math.ceil(minutes / NEW_LEARNING_SHARE / daysLeft);
        }
        return new Pace(lessons, days, finish, onTrack, required);
    }
}
