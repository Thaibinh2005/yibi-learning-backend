package com.yibi.backend.dto.learningpath;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AssessmentAnswerRequest {
    @NotBlank
    private String questionId;

    private String answer;

    @Min(0)
    private Integer responseTimeSeconds = 0;
}
