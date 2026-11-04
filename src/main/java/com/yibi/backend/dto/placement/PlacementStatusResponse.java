package com.yibi.backend.dto.placement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlacementStatusResponse {

    private boolean onboardingCompleted;
    private String startMode;
    private Integer selfReportedLevel;
    private Integer hskLevel;
    /** The unfinished attempt to resume, if any. */
    private Long inProgressAttemptId;
    /** How many questions the unfinished attempt has answered so far. */
    private Integer inProgressAnswered;
    /** The latest finished attempt, whose result can be shown. */
    private Long latestAttemptId;
    /** Whether the question bank has enough published questions for a full-length test. */
    private boolean poolReady;
    /** Whether the speech service answers; without it the test leaves out the speaking questions. */
    private boolean speakingAvailable;
}
