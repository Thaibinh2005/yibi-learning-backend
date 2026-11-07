package com.yibi.backend.service.plan;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turns a learner's state into today's work. It has no database access so every rule can be tested directly.
 *
 * <p>Order: flashcards due, questions the learner got wrong, then adaptive practice for reviews and weak skills, then lessons in the remaining
 * time: those the learner got wrong first, then new ones. Reviews may use at most half the time, so a large
 * backlog cannot stop new learning.
 */
public final class DailyPlanner {

    public static final String REVIEW_CARDS = "REVIEW_CARDS";
    public static final String REVIEW_MISTAKES = "REVIEW_MISTAKES";
    public static final String PRACTICE = "PRACTICE";
    public static final String LESSON = "LESSON";

    private static final double MINUTES_PER_CARD = 0.5;
    private static final double MINUTES_PER_MISTAKE = 0.5;
    /** The length of one mistakes review. */
    public static final int MISTAKES_PER_REVIEW = 10;
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
            "LESSON", 6, "READING", 8, "LISTENING", 8, "DIALOGUE", 6, "CHECKPOINT", 6);

    private DailyPlanner() { }

    /** A skill to reinforce; urgent ones (high priority) get their lessons before the others. */
    public record SkillNeed(String key, String label, String lessonType, boolean urgent) { }

    /** An unfinished lesson; wrongAnswers counts the learner's wrong answers to questions from it. */
    public record LessonCandidate(long id, String title, String contentType, int hskLevel, int wrongAnswers) {
        public LessonCandidate(long id, String title, String contentType, int hskLevel) {
            this(id, title, contentType, hskLevel, 0);
        }
    }

    /** @param hasAnswers false until the learner has answered anything, which gets them a short first practice */
    public record Input(int minutes, String levelCode, int dueCards, int dueTopics,
                        List<SkillNeed> needs, List<LessonCandidate> lessons, boolean hasAnswers, int openMistakes) {
        public Input(int minutes, String levelCode, int dueCards, int dueTopics,
                     List<SkillNeed> needs, List<LessonCandidate> lessons, boolean hasAnswers) {
            this(minutes, levelCode, dueCards, dueTopics, needs, lessons, hasAnswers, 0);
        }
    }

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

        int mistakes = Math.min(Math.min(in.openMistakes(), MISTAKES_PER_REVIEW),
                (int) ((reviewBudget - (in.minutes() - remaining)) / MINUTES_PER_MISTAKE));
        if (mistakes > 0) {
            int minutes = (int) Math.ceil(mistakes * MINUTES_PER_MISTAKE);
            items.add(new Item(REVIEW_MISTAKES, "Ôn " + mistakes + " câu từng sai",
                    "Đúng 2 lần liên tiếp thì câu đó mới được coi là đã thuộc.", minutes, "/learning-path/mistakes", null));
            remaining -= minutes;
        }

        // Practice leaves room for the next lesson, so reviews alone never fill a day.
        List<LessonCandidate> ordered = orderLessons(in);
        int forPractice = remaining - (ordered.isEmpty() ? 0 : lessonMinutes(ordered.get(0).contentType()));
        int questions = practiceQuestions(in, forPractice, reviewBudget - (in.minutes() - remaining));
        if (!in.hasAnswers() && forPractice >= MIN_PRACTICE_QUESTIONS) {
            items.add(new Item(PRACTICE, "Làm quen: luyện " + MIN_PRACTICE_QUESTIONS + " câu",
                    "Vài câu đầu giúp hệ thống biết kỹ năng nào bạn cần củng cố.", MIN_PRACTICE_QUESTIONS,
                    practiceLink(in, MIN_PRACTICE_QUESTIONS), null));
            remaining -= MIN_PRACTICE_QUESTIONS;
        } else if (questions > 0) {
            items.add(new Item(PRACTICE, "Luyện " + questions + " câu thích ứng", practiceDetail(in), questions,
                    practiceLink(in, questions), null));
            remaining -= questions;
        }

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

    private static String practiceLink(Input in, int questions) {
        return "/learning-path/practice?level=" + in.levelCode() + "&limit=" + questions;
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

    /** Lessons the learner got wrong first, most mistakes first, then the path order given by the roadmap. */
    private static List<LessonCandidate> orderLessons(Input in) {
        List<LessonCandidate> ordered = new ArrayList<>(in.lessons().stream()
                .filter(lesson -> lesson.wrongAnswers() > 0)
                .sorted(Comparator.comparingInt(LessonCandidate::wrongAnswers).reversed())
                .toList());
        in.lessons().stream().filter(lesson -> lesson.wrongAnswers() == 0).forEach(ordered::add);
        return ordered;
    }

    private static Item lessonItem(LessonCandidate lesson, int minutes, Input in) {
        boolean weak = in.needs().stream().anyMatch(need -> lesson.contentType().equals(need.lessonType()));
        String detail = lesson.wrongAnswers() > 0 ? "Bạn đã sai " + lesson.wrongAnswers() + " câu ở bài này, nên xem lại."
                : weak ? "Bài này giúp củng cố kỹ năng đang yếu." : "Bài tiếp theo trong lộ trình của bạn.";
        return new Item(LESSON, lesson.title(), detail, minutes,
                "/learning-path/lesson/" + lesson.id(), lesson.id());
    }

    public static int lessonMinutes(String contentType) {
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
