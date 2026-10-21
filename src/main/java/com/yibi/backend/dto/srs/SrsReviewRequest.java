package com.yibi.backend.dto.srs;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SrsReviewRequest {

    @NotNull(message = "Card ID is required")
    private Long cardId;

    /**
     * SM-2 Review Grade (0-5):
     * 0: Complete blackout
     * 1: Incorrect; remembered upon seeing answer
     * 2: Incorrect; easy to recall upon seeing answer
     * 3: Correct with serious difficulty
     * 4: Correct after hesitation
     * 5: Perfect recall
     */
    @NotNull(message = "Review grade is required (0-5)")
    @Min(value = 0, message = "Grade must be between 0 and 5")
    @Max(value = 5, message = "Grade must be between 0 and 5")
    private Integer grade;
}
