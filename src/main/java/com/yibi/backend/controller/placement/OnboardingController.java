package com.yibi.backend.controller.placement;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.placement.SurveyRequest;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.PlacementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final PlacementService placementService;
    private final SecurityUtils securityUtils;

    @PostMapping("/survey")
    public ResponseEntity<ApiResponse<String>> saveSurvey(@Valid @RequestBody SurveyRequest request) {
        placementService.saveSurvey(securityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.success("Survey saved"));
    }
}
