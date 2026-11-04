package com.yibi.backend.controller.placement;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.placement.*;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.PlacementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/placement")
@RequiredArgsConstructor
public class PlacementController {

    private final PlacementService placementService;
    private final SecurityUtils securityUtils;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<PlacementStatusResponse>> getStatus() {
        return ResponseEntity.ok(ApiResponse.success(placementService.getStatus(currentUser())));
    }

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<PlacementStepResponse>> start() {
        return ResponseEntity.ok(ApiResponse.success(placementService.start(currentUser())));
    }

    @PostMapping("/{attemptId}/answer")
    public ResponseEntity<ApiResponse<PlacementStepResponse>> answer(
            @PathVariable Long attemptId, @Valid @RequestBody PlacementAnswerRequest request) {
        return ResponseEntity.ok(ApiResponse.success(placementService.answer(currentUser(), attemptId, request)));
    }

    @PostMapping(value = "/{attemptId}/speaking", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<PlacementStepResponse>> answerSpeaking(
            @PathVariable Long attemptId,
            @RequestParam Long questionId,
            @RequestParam MultipartFile audio,
            @RequestParam(required = false) Integer responseTimeSeconds) {
        return ResponseEntity.ok(ApiResponse.success(
                placementService.answerSpeaking(currentUser(), attemptId, questionId, audio, responseTimeSeconds)));
    }

    @PostMapping("/{attemptId}/skip")
    public ResponseEntity<ApiResponse<PlacementStepResponse>> skipSpeaking(
            @PathVariable Long attemptId, @RequestParam Long questionId) {
        return ResponseEntity.ok(ApiResponse.success(placementService.skipSpeaking(currentUser(), attemptId, questionId)));
    }

    @PostMapping("/{attemptId}/abandon")
    public ResponseEntity<ApiResponse<String>> abandon(@PathVariable Long attemptId) {
        placementService.abandon(currentUser(), attemptId);
        return ResponseEntity.ok(ApiResponse.success("Attempt abandoned"));
    }

    @GetMapping("/{attemptId}/result")
    public ResponseEntity<ApiResponse<PlacementResultResponse>> getResult(@PathVariable Long attemptId) {
        return ResponseEntity.ok(ApiResponse.success(placementService.getResult(currentUser(), attemptId)));
    }

    private UUID currentUser() {
        return securityUtils.getCurrentUserId();
    }
}
