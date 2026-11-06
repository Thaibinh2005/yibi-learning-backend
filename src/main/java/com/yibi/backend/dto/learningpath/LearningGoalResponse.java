package com.yibi.backend.dto.learningpath;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningGoalResponse {
    private String goalType;
    private Integer targetLevel;
    private Integer targetScore;
    private LocalDate targetDate;
    private Integer dailyMinutes;
    /** False until the learner has saved a goal; the values above are then the defaults. */
    private boolean goalSet;
}
