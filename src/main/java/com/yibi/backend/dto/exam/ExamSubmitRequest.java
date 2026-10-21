package com.yibi.backend.dto.exam;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamSubmitRequest {

    @NotNull(message = "Exam ID is required")
    private Long examId;

    /** Map of Question ID to chosen answer (e.g. {"1": "A", "2": "C"}) */
    @NotNull(message = "Answers are required")
    private Map<Long, String> answers;

    private Integer timeTakenSeconds;
}
