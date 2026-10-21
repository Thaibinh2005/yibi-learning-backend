package com.yibi.backend.dto.exam;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamAttemptResponse {

    private Long attemptId;
    private Long examId;
    private String examTitle;
    private BigDecimal score;
    private Boolean passed;
    private Integer correctCount;
    private Integer totalQuestions;
    private Integer timeTakenSeconds;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
