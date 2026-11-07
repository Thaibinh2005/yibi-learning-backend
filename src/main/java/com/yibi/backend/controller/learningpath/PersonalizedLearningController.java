package com.yibi.backend.controller.learningpath;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.learningpath.*;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.LearningGoalService;
import com.yibi.backend.service.PersonalizedLearningService;
import com.yibi.backend.service.plan.Roadmap;
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
    private final LearningGoalService learningGoalService;
    private final SecurityUtils securityUtils;

    @GetMapping
    public ResponseEntity<ApiResponse<LearningPathOverviewResponse>> getOverview() {
        UUID userId = securityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(learningService.getOverview(userId)));
    }

    /** What to study today, recomputed from the learner's latest results each time it is read. */
    @GetMapping("/today")
    public ResponseEntity<ApiResponse<DailyPlanResponse>> getTodayPlan() {
        return ResponseEntity.ok(ApiResponse.success(learningService.getTodayPlan(securityUtils.getCurrentUserId())));
    }

    /** One level of the path, by default the learner's own, with what is open and what is locked. */
    @GetMapping("/roadmap")
    public ResponseEntity<ApiResponse<Roadmap.Result>> getRoadmap(@RequestParam(required = false) Integer level) {
        return ResponseEntity.ok(ApiResponse.success(learningService.getRoadmap(securityUtils.getCurrentUserId(), level)));
    }

    @PutMapping("/goal")
    public ResponseEntity<ApiResponse<LearningGoalResponse>> updateGoal(@Valid @RequestBody LearningGoalRequest request) {
        return ResponseEntity.ok(ApiResponse.success(learningGoalService.save(securityUtils.getCurrentUserId(), request)));
    }

}
