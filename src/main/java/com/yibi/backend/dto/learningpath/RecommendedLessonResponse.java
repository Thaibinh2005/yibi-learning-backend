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
    /** Course level (7 = the HSK 7-9 band), used to link straight to the lesson. */
    private Integer hskLevel;
    private String contentType;
}
