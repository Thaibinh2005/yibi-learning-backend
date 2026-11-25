package com.yibi.backend.controller.mocktest;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.MockTestService;
import com.yibi.backend.service.MockTestService.Attempt;
import com.yibi.backend.service.MockTestService.AttemptView;
import com.yibi.backend.service.MockTestService.Summary;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Official HSK 3.0 sample papers.
 *
 * GET    /api/mock-tests                       → papers with the learner's open attempt and last result
 * POST   /api/mock-tests/{testId}/attempts     → resume the open attempt, or start one ({"mode": "PRACTICE" | "EXAM"})
 * GET    /api/mock-tests/attempts/{id}         → an attempt and its paper (no answers during a timed exam)
 * PUT    /api/mock-tests/attempts/{id}         → save answers, position and time
 * POST   /api/mock-tests/attempts/{id}/submit  → hand in and score
 * DELETE /api/mock-tests/attempts/{id}         → throw away an unfinished attempt
 */
@RestController
@RequestMapping("/api/mock-tests")
@RequiredArgsConstructor
public class MockTestController {

    private final MockTestService service;
    private final SecurityUtils securityUtils;

    public record StartRequest(String mode) {}

    public record ProgressRequest(Map<String, String> answers, Integer currentIndex, Integer elapsedSeconds) {
        int index() { return currentIndex == null ? 0 : currentIndex; }
        int elapsed() { return elapsedSeconds == null ? 0 : elapsedSeconds; }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Summary>>> list() {
        return ResponseEntity.ok(ApiResponse.success(service.list(currentUserOrNull())));
    }

    /** The list is open to guests; they get it without progress. */
    private java.util.UUID currentUserOrNull() {
        try {
            return securityUtils.getCurrentUserId();
        } catch (com.yibi.backend.exception.UnauthorizedException guest) {
            return null;
        }
    }

    @PostMapping("/{testId}/attempts")
    public ResponseEntity<ApiResponse<AttemptView>> start(@PathVariable String testId, @RequestBody StartRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.start(securityUtils.getCurrentUserId(), testId, request.mode())));
    }

    @GetMapping("/attempts/{id}")
    public ResponseEntity<ApiResponse<AttemptView>> view(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(service.view(securityUtils.getCurrentUserId(), id)));
    }

    @PutMapping("/attempts/{id}")
    public ResponseEntity<ApiResponse<Attempt>> save(@PathVariable long id, @RequestBody ProgressRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.save(securityUtils.getCurrentUserId(), id, request.answers(), request.index(), request.elapsed())));
    }

    @PostMapping("/attempts/{id}/submit")
    public ResponseEntity<ApiResponse<AttemptView>> submit(@PathVariable long id, @RequestBody ProgressRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.submit(securityUtils.getCurrentUserId(), id, request.answers(), request.index(), request.elapsed())));
    }

    @DeleteMapping("/attempts/{id}")
    public ResponseEntity<ApiResponse<Void>> discard(@PathVariable long id) {
        service.discard(securityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(ApiResponse.success("Đã xoá bài đang làm.", null));
    }
}
