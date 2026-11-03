package com.yibi.backend.controller.practice;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.practice.AdaptiveSessionRequest;
import com.yibi.backend.dto.practice.MasterySummaryResponse;
import com.yibi.backend.dto.practice.PracticeAnswerRequest;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.QuestionBankService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/practice")
@RequiredArgsConstructor
public class QuestionPracticeController {

    private final QuestionBankService questionBankService;
    private final SecurityUtils securityUtils;

    @PostMapping("/lessons/{lessonId}/sessions")
    public ResponseEntity<ApiResponse<Map<String, Long>>> startLessonSession(@PathVariable Long lessonId) {
        Long sessionId = questionBankService.startLessonSession(lessonId, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("sessionId", sessionId)));
    }

    @PostMapping("/adaptive-sessions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startAdaptiveSession(
            @Valid @RequestBody AdaptiveSessionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(questionBankService.startAdaptiveSession(
                securityUtils.getCurrentUserId(), request.getLevelCode(), request.getLimit())));
    }

    @GetMapping("/mastery-summary")
    public ResponseEntity<ApiResponse<MasterySummaryResponse>> getMasterySummary() {
        return ResponseEntity.ok(ApiResponse.success(
                questionBankService.getMasterySummary(securityUtils.getCurrentUserId())));
    }

    @PostMapping("/sessions/{sessionId}/answers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitAnswer(
            @PathVariable Long sessionId,
            @Valid @RequestBody PracticeAnswerRequest request) {
        return ResponseEntity.ok(ApiResponse.success(questionBankService.submitAnswer(
                sessionId, securityUtils.getCurrentUserId(), request.getQuestionCode(),
                request.getAnswer(), request.getResponseTimeMs())));
    }
}
