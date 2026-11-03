package com.yibi.backend.dto.learningpath;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

@Value
@Builder
public class AssessmentResultResponse {
    Long assessmentId;
    Integer correctCount;
    Integer totalQuestions;
    BigDecimal scorePercent;
    String levelSummary;
    List<SkillStatResponse> skills;
}
