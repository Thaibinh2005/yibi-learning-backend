package com.yibi.backend.controller.learningpath;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.learningpath.*;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.PersonalizedLearningService;
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

}
