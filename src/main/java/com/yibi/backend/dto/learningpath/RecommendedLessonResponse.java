package com.yibi.backend.dto.learningpath;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RecommendedLessonResponse {
    Long lessonId;
    Integer lessonNumber;
    String title;
    String reason;
}
