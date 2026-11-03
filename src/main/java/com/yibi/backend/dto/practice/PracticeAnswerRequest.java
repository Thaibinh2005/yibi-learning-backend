package com.yibi.backend.dto.practice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PracticeAnswerRequest {
    @NotBlank
    @Size(max = 120)
    private String questionCode;

    @NotBlank
    @Size(max = 4000)
    private String answer;

    private Integer responseTimeMs;
}
