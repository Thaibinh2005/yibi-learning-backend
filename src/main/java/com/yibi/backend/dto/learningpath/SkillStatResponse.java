package com.yibi.backend.dto.learningpath;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class SkillStatResponse {
    String skillKey;
    String label;
    Integer attempts;
    Integer accuracyPercent;
    String priority;
    String confidence;
    LocalDateTime lastPracticedAt;
}
