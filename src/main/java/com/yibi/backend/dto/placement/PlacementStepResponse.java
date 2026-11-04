package com.yibi.backend.dto.placement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The next thing to show: a question while the test runs, or {@code finished} once the result is ready. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlacementStepResponse {

    private Long attemptId;
    private boolean finished;
    private PlacementQuestionDto question;
    private int answered;
    private int minimumQuestions;
    private int maximumQuestions;
}
