package com.yibi.backend.dto.learningpath;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

@Value
@Builder
public class LearningPathOverviewResponse {
    boolean assessmentCompleted;
    Integer availableHskLevel;
    BigDecimal latestScorePercent;
    String levelSummary;
    List<SkillStatResponse> skills;
    List<RecommendedLessonResponse> recommendedLessons;
}
