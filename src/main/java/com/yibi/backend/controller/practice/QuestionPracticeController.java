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
    private final com.yibi.backend.service.PracticeSpeakingService speakingService;
    private final SecurityUtils securityUtils;
    private final com.yibi.backend.service.LessonAccessService lessonAccess;

    @PostMapping("/lessons/{lessonId}/sessions")
    public ResponseEntity<ApiResponse<Map<String, Long>>> startLessonSession(@PathVariable Long lessonId) {
        lessonAccess.requireLesson(lessonId);
        Long sessionId = questionBankService.startLessonSession(lessonId, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("sessionId", sessionId)));
    }

    @PostMapping("/adaptive-sessions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startAdaptiveSession(
            @Valid @RequestBody AdaptiveSessionRequest request) {
        lessonAccess.requireLevelCode(request.getLevelCode());
        return ResponseEntity.ok(ApiResponse.success(questionBankService.startAdaptiveSession(
                securityUtils.getCurrentUserId(), request.getLevelCode(), request.getLimit(), speakingService.isAvailable())));
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

    @PostMapping(value = "/sessions/{sessionId}/speaking", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> answerSpeaking(
            @PathVariable Long sessionId,
            @RequestParam String questionCode,
            @RequestParam org.springframework.web.multipart.MultipartFile audio,
            @RequestParam(required = false) Integer responseTimeMs) {
        return ResponseEntity.ok(ApiResponse.success(speakingService.answer(
                sessionId, securityUtils.getCurrentUserId(), questionCode, audio, responseTimeMs)));
    }
}
