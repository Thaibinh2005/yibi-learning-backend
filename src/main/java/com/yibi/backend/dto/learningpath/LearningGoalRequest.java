package com.yibi.backend.dto.learningpath;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningGoalRequest {

    @NotBlank(message = "Goal type is required")
    @Pattern(regexp = "HSK|HSKK|COMMUNICATION", message = "Goal type must be HSK, HSKK or COMMUNICATION")
    private String goalType;

    /** 1-6, or 7 for the combined HSK 7-9 band. */
    @NotNull(message = "Target level is required")
    @Min(value = 1, message = "Target level must be 1 to 7")
    @Max(value = 7, message = "Target level must be 1 to 7")
    private Integer targetLevel;

    @Min(value = 0, message = "Target score must be 0 to 300")
    @Max(value = 300, message = "Target score must be 0 to 300")
    private Integer targetScore;

    private LocalDate targetDate;

    @NotNull(message = "Daily minutes is required")
    @Min(value = 5, message = "Daily minutes must be 5 to 240")
    @Max(value = 240, message = "Daily minutes must be 5 to 240")
    private Integer dailyMinutes;
}
