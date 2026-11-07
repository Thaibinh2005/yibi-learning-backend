package com.yibi.backend.dto.placement;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SurveyRequest {

    /** The level the learner picked, 0 (none) to 9. */
    @NotNull(message = "Self-reported level is required")
    @Min(value = 0, message = "Level must be 0 to 9")
    @Max(value = 9, message = "Level must be 0 to 9")
    private Integer selfLevel;

    /** BASIC starts from HSK 1 without a test; PLACEMENT leads to the placement test. */
    @NotBlank(message = "Start mode is required")
    @Pattern(regexp = "BASIC|PLACEMENT", message = "Start mode must be BASIC or PLACEMENT")
    private String startMode;

    /** Optional: the learner's goal and daily study time. */
    @jakarta.validation.Valid
    private com.yibi.backend.dto.learningpath.LearningGoalRequest goal;
}
