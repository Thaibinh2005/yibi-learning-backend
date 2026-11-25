package com.yibi.backend.service;

import com.yibi.backend.repository.SrsCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/** Real study time and daily-goal progress. Time comes from the browser's activity pings; a day counts once anything was done. */
@Service
@RequiredArgsConstructor
public class StudyStatsService {

    /** One ping can claim at most this much, so a stuck or forged client cannot inflate the day. */
    private static final int MAX_SECONDS_PER_PING = 60;
    private static final int WEEK_DAYS = 7;

    private final JdbcTemplate jdbc;
    private final LearningGoalService learningGoalService;
    private final SrsCardRepository srsCardRepository;

    public record DayStat(LocalDate date, int minutes, boolean goalMet) { }

    /**
     * {@code answersToday}/{@code accuracyToday}: questions answered today in the learning path and in practice;
     * {@code cardsReviewedToday}: flashcards and writing cards reviewed today; {@code weekMinutes}: the last 7 days.
     */
    public record Stats(int streakDays, int todayMinutes, int dailyGoalMinutes, int goalPercent, int answersToday,
                        int accuracyToday, int nodesCompleted, long dueCards, Integer bestExamScore, Integer bestExamLevel,
                        List<DayStat> week, Integer targetScore, Integer bestTowardTarget, Integer targetScale,
                        int cardsReviewedToday, int weekMinutes, int weekGoalDays, int longestStreak) { }

    @Transactional
    public void recordActivity(UUID userId, int seconds) {
        int counted = Math.max(0, Math.min(seconds, MAX_SECONDS_PER_PING));
        if (counted == 0) return;
        jdbc.update("""
                INSERT INTO study_activity (user_id, study_date, seconds) VALUES (?, ?, ?)
                ON CONFLICT (user_id, study_date) DO UPDATE SET seconds = study_activity.seconds + EXCLUDED.seconds
                """, userId, LocalDate.now(), counted);
    }

    @Transactional(readOnly = true)
    public Stats get(UUID userId) {
        LocalDate today = LocalDate.now();
        int goal = learningGoalService.get(userId).getDailyMinutes();

        Map<LocalDate, Integer> secondsByDay = new HashMap<>();
        jdbc.query("SELECT study_date, seconds FROM study_activity WHERE user_id = ?",
                rs -> { secondsByDay.put(rs.getDate(1).toLocalDate(), rs.getInt(2)); }, userId);
        Set<LocalDate> activeDays = new HashSet<>(secondsByDay.keySet());
        // A day counts as studied once anything was done: answers, card reviews, a speaking test, a listening lesson.
        // srs_cards.last_reviewed is written by Hibernate in UTC; the others are TIMESTAMPTZ, a date, or session-local.
        jdbc.queryForList("""
                SELECT DISTINCT answered_at::date FROM path_answers WHERE user_id = ?
                UNION SELECT DISTINCT attempted_at::date FROM question_attempts WHERE user_id = ?
                UNION SELECT DISTINCT (last_reviewed AT TIME ZONE 'UTC')::date FROM srs_cards WHERE user_id = ? AND last_reviewed IS NOT NULL
                UNION SELECT DISTINCT last_reviewed::date FROM srs_writing_cards WHERE user_id = ? AND last_reviewed IS NOT NULL
                UNION SELECT DISTINCT finished_at::date FROM hskk_attempts WHERE user_id = ? AND finished_at IS NOT NULL
                UNION SELECT DISTINCT day FROM lesson_practice_days WHERE user_id = ?
                """, Date.class, userId, userId, userId, userId, userId, userId).forEach(day -> activeDays.add(day.toLocalDate()));

        int[] todayAnswers = jdbc.query("""
                SELECT count(*), count(*) FILTER (WHERE ok) FROM (
                    SELECT is_correct AS ok FROM path_answers WHERE user_id = ? AND answered_at::date = ?
                    UNION ALL
                    SELECT is_correct FROM question_attempts WHERE user_id = ? AND attempted_at::date = ?
                ) a
                """, rs -> { rs.next(); return new int[]{rs.getInt(1), rs.getInt(2)}; }, userId, today, userId, today);
        Integer cards = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM srs_cards WHERE user_id = ? AND (last_reviewed AT TIME ZONE 'UTC')::date = ?)
                     + (SELECT count(*) FROM srs_writing_cards WHERE user_id = ? AND last_reviewed::date = ?)
                """, Integer.class, userId, today, userId, today);
        Integer completed = jdbc.queryForObject(
                "SELECT count(*) FROM user_path_progress WHERE user_id = ? AND completed_at IS NOT NULL", Integer.class, userId);
        int[] bestExam = jdbc.query("""
                SELECT score_percent, hsk_level FROM mock_exam_results WHERE user_id = ?
                ORDER BY score_percent DESC, finished_at DESC LIMIT 1
                """, rs -> rs.next() ? new int[]{rs.getInt(1), rs.getInt(2)} : null, userId);

        Integer[] progress = targetProgress(userId);

        List<DayStat> week = new ArrayList<>();
        for (int back = WEEK_DAYS - 1; back >= 0; back--) {
            LocalDate day = today.minusDays(back);
            int minutes = secondsByDay.getOrDefault(day, 0) / 60;
            // Without a daily goal no day is "met": an empty day must not get a tick.
            week.add(new DayStat(day, minutes, goal > 0 && minutes > 0 && minutes >= goal));
        }
        int todayMinutes = secondsByDay.getOrDefault(today, 0) / 60;
        int weekMinutes = week.stream().mapToInt(DayStat::minutes).sum();
        int weekGoalDays = (int) week.stream().filter(DayStat::goalMet).count();
        return new Stats(streak(activeDays, today), todayMinutes, goal, goalPercent(todayMinutes, goal),
                todayAnswers[0], todayAnswers[0] == 0 ? 0 : Math.round(todayAnswers[1] * 100f / todayAnswers[0]),
                completed == null ? 0 : completed, srsCardRepository.countDueCards(userId, today),
                bestExam == null ? null : bestExam[0], bestExam == null ? null : bestExam[1], week,
                progress == null ? null : progress[0], progress == null ? null : progress[1], progress == null ? null : progress[2],
                cards == null ? 0 : cards, weekMinutes, weekGoalDays, longestStreak(activeDays));
    }

    /** The target, the best estimate so far on that test, and its scale; null without a target score. */
    private Integer[] targetProgress(UUID userId) {
        return jdbc.query("SELECT goal_type, target_hsk_level, target_score FROM learning_paths WHERE user_id = ? AND target_score IS NOT NULL", rs -> {
            if (!rs.next()) return null;
            String type = rs.getString(1);
            int level = rs.getInt(2);
            int scale = com.yibi.backend.service.plan.ScoreGoal.scale(type, level);
            if (scale == 0) return null;
            Integer best = "HSKK".equals(type)
                    ? jdbc.queryForObject("SELECT max(score_percent) FROM hskk_attempts WHERE user_id = ? AND finished_at IS NOT NULL", Integer.class, userId)
                    : jdbc.queryForObject("SELECT max(score_percent) FROM mock_exam_results WHERE user_id = ? AND hsk_level = ?", Integer.class, userId, level);
            return new Integer[] {rs.getInt(3), best == null ? null : com.yibi.backend.service.plan.ScoreGoal.estimatedScore(best, scale), scale};
        }, userId);
    }

    /** Consecutive active days ending today, or yesterday when nothing has been done yet today. */
    static int streak(Set<LocalDate> activeDays, LocalDate today) {
        LocalDate day = activeDays.contains(today) ? today : today.minusDays(1);
        int count = 0;
        while (activeDays.contains(day)) {
            count++;
            day = day.minusDays(1);
        }
        return count;
    }

    /** The longest run of consecutive studied days ever. */
    static int longestStreak(Set<LocalDate> activeDays) {
        int best = 0;
        for (LocalDate day : activeDays) {
            if (activeDays.contains(day.minusDays(1))) continue;   // count only from the first day of a run
            int run = 0;
            while (activeDays.contains(day.plusDays(run))) run++;
            best = Math.max(best, run);
        }
        return best;
    }

    static int goalPercent(int minutes, int goal) {
        return goal <= 0 ? 0 : Math.min(100, minutes * 100 / goal);
    }
}
