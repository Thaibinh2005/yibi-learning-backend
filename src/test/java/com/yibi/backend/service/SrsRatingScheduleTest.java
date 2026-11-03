package com.yibi.backend.service;

import com.yibi.backend.dto.srs.SrsRateRequest.Rating;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SrsRatingScheduleTest {

    private static final BigDecimal EF = new BigDecimal("2.50");

    private static int interval(Rating r, int reps, int interval) {
        return SrsService.scheduleRating(r, reps, interval, EF).intervalDays();
    }

    @Test
    void newCardIntervals() {
        assertEquals(0, interval(Rating.AGAIN, 0, 1));
        assertEquals(1, interval(Rating.HARD, 0, 1));
        assertEquals(1, interval(Rating.GOOD, 0, 1));
        assertEquals(3, interval(Rating.EASY, 0, 1));
    }

    @Test
    void intervalsStayWithinBounds() {
        for (int reps = 0; reps < 10; reps++) {
            for (int iv = 0; iv <= 30; iv++) {
                int hard = interval(Rating.HARD, reps, iv);
                int good = interval(Rating.GOOD, reps, iv);
                int easy = interval(Rating.EASY, reps, iv);
                assertTrue(hard >= 1 && hard <= 3);
                assertTrue(good >= 1 && good <= 4);
                assertTrue(easy >= 1 && easy <= 7);
                assertTrue(good >= hard && easy > good);
            }
        }
    }

    @Test
    void againResetsRepetitions() {
        assertEquals(0, SrsService.scheduleRating(Rating.AGAIN, 5, 4, EF).repetitions());
    }
}
