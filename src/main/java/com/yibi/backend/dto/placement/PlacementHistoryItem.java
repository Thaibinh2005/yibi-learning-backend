package com.yibi.backend.dto.placement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One finished placement attempt in the learner's history. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlacementHistoryItem {
    private Long attemptId;
    /** Estimated HSK level, 1 to 9. */
    private int level;
    /** LOW, MEDIUM or HIGH. */
    private String confidence;
    private int questionCount;
    private int correctCount;
    /** Moments in time, sent with a time zone ("...Z") so the browser shows them in local time. */
    private Instant startedAt;
    private Instant finishedAt;
    /** Time spent answering, in seconds. */
    private int durationSeconds;
}
