package com.yibi.backend.dto.learningpath;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class PlacementTestResponse {
    Long assessmentId;
    Integer hskLevel;
    String notice;
    List<PlacementQuestionResponse> questions;
}
