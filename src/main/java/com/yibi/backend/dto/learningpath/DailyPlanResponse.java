package com.yibi.backend.dto.learningpath;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyPlanResponse {
    private LearningGoalResponse goal;
    private int currentLevel;
    private int minutesBudget;
    private int minutesPlanned;
    private List<Item> items;
    private int remainingLessons;
    private int estimatedDays;
    private LocalDate estimatedFinishDate;
    /** Null when the learner set no target date. */
    private Boolean onTrack;
    /** Minutes per day needed to finish by the target date; null when there is no usable date. */
    private Integer requiredDailyMinutes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        /** REVIEW_CARDS, PRACTICE or LESSON */
        private String type;
        private String title;
        private String detail;
        private int minutes;
        private String link;
        private Long lessonId;
    }
}
