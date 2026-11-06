package com.yibi.backend.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StudyStatsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    @Test
    void streakCountsBackFromToday() {
        assertEquals(3, StudyStatsService.streak(Set.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(2)), TODAY));
    }

    @Test
    void notYetStudyingTodayDoesNotBreakTheStreak() {
        assertEquals(2, StudyStatsService.streak(Set.of(TODAY.minusDays(1), TODAY.minusDays(2)), TODAY));
    }

    @Test
    void aMissedDayEndsTheStreak() {
        assertEquals(1, StudyStatsService.streak(Set.of(TODAY, TODAY.minusDays(2)), TODAY));
        assertEquals(0, StudyStatsService.streak(Set.of(TODAY.minusDays(2)), TODAY));
    }

    @Test
    void goalPercentStopsAtOneHundred() {
        assertEquals(50, StudyStatsService.goalPercent(10, 20));
        assertEquals(100, StudyStatsService.goalPercent(45, 20));
        assertEquals(0, StudyStatsService.goalPercent(10, 0));
    }
}
