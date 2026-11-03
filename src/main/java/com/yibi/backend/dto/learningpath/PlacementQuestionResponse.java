package com.yibi.backend.dto.learningpath;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class PlacementQuestionResponse {
    String id;
    String skillKey;
    String skillLabel;
    String topic;
    Integer lessonNumber;
    String prompt;
    List<String> options;
}
