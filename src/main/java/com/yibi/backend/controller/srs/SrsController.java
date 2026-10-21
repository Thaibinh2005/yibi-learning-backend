package com.yibi.backend.controller.srs;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.srs.SrsCardResponse;
import com.yibi.backend.dto.srs.SrsReviewRequest;
import com.yibi.backend.dto.srs.SrsStatsResponse;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.SrsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/srs")
@RequiredArgsConstructor
public class SrsController {

    private final SrsService srsService;
    private final SecurityUtils securityUtils;

    @GetMapping("/due")
    public ResponseEntity<ApiResponse<List<SrsCardResponse>>> getDueCards() {
        UUID userId = securityUtils.getCurrentUserId();
        List<SrsCardResponse> dueCards = srsService.getDueCards(userId);
        return ResponseEntity.ok(ApiResponse.success(dueCards));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<SrsStatsResponse>> getStats() {
        UUID userId = securityUtils.getCurrentUserId();
        SrsStatsResponse stats = srsService.getSrsStats(userId);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @PostMapping("/add/{vocabularyId}")
    public ResponseEntity<ApiResponse<SrsCardResponse>> addCard(@PathVariable Long vocabularyId) {
        UUID userId = securityUtils.getCurrentUserId();
        SrsCardResponse card = srsService.addCard(userId, vocabularyId);
        return ResponseEntity.ok(ApiResponse.success("Card added to your SRS deck", card));
    }

    @PostMapping("/review")
    public ResponseEntity<ApiResponse<SrsCardResponse>> reviewCard(@Valid @RequestBody SrsReviewRequest request) {
        UUID userId = securityUtils.getCurrentUserId();
        SrsCardResponse card = srsService.reviewCard(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Review recorded", card));
    }
}
