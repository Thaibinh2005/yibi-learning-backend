package com.yibi.backend.controller;

import com.yibi.backend.dto.admin.AdminDtos.CreateReportRequest;
import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.ContentReportService;
import com.yibi.backend.service.ListeningViewer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A signed-in learner reports a mistake in a listening line or lesson, or in a word. */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ContentReportController {

    private final ContentReportService reports;
    private final SecurityUtils securityUtils;

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> report(@Valid @RequestBody CreateReportRequest request) {
        reports.create(ListeningViewer.of(securityUtils.getCurrentUser()), request);
        return ResponseEntity.ok(ApiResponse.success("Cảm ơn bạn! Admin sẽ xem và sửa lỗi này.", null));
    }
}
