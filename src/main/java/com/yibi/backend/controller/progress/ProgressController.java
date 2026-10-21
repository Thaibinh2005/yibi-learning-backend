package com.yibi.backend.controller.progress;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.progress.OverallProgressResponse;
import com.yibi.backend.dto.progress.ProgressUpdateDto;
import com.yibi.backend.dto.progress.UserProgressResponse;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.ProgressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/progress")
@RequiredArgsConstructor
public class ProgressController {

    private final ProgressService progressService;
    private final SecurityUtils securityUtils;

    @GetMapping("/my-lessons")
    public ResponseEntity<ApiResponse<List<UserProgressResponse>>> getUserProgress() {
        UUID userId = securityUtils.getCurrentUserId();
        List<UserProgressResponse> progress = progressService.getUserProgress(userId);
        return ResponseEntity.ok(ApiResponse.success(progress));
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<OverallProgressResponse>> getOverallProgress() {
        UUID userId = securityUtils.getCurrentUserId();
        OverallProgressResponse overview = progressService.getOverallProgress(userId);
        return ResponseEntity.ok(ApiResponse.success(overview));
    }

    @PostMapping("/update")
    public ResponseEntity<ApiResponse<UserProgressResponse>> updateProgress(@Valid @RequestBody ProgressUpdateDto dto) {
        UUID userId = securityUtils.getCurrentUserId();
        UserProgressResponse result = progressService.updateProgress(userId, dto);
        return ResponseEntity.ok(ApiResponse.success("Progress updated", result));
    }
}
