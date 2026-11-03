package com.yibi.backend.dto.learningpath;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AssessmentSubmitRequest {
    @NotNull
    @Valid
    private List<AssessmentAnswerRequest> answers = new ArrayList<>();
}
