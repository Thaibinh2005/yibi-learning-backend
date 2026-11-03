package com.yibi.backend.controller.learningpath;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.learningpath.*;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.PersonalizedLearningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/learning-path")
@RequiredArgsConstructor
public class PersonalizedLearningController {

    private final PersonalizedLearningService learningService;
    private final SecurityUtils securityUtils;

    @GetMapping
    public ResponseEntity<ApiResponse<LearningPathOverviewResponse>> getOverview() {
        UUID userId = securityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(learningService.getOverview(userId)));
    }

    @PostMapping("/assessment")
    public ResponseEntity<ApiResponse<PlacementTestResponse>> startAssessment() {
        UUID userId = securityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(learningService.startAssessment(userId)));
    }

    @PostMapping("/assessment/{assessmentId}/submit")
    public ResponseEntity<ApiResponse<AssessmentResultResponse>> submitAssessment(
            @PathVariable Long assessmentId,
            @Valid @RequestBody AssessmentSubmitRequest request) {
        UUID userId = securityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(
                learningService.submitAssessment(userId, assessmentId, request)));
    }
}
