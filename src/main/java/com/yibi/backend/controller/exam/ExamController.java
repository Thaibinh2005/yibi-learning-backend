package com.yibi.backend.controller.exam;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.exam.ExamAttemptResponse;
import com.yibi.backend.dto.exam.ExamDetailResponse;
import com.yibi.backend.dto.exam.ExamResponse;
import com.yibi.backend.dto.exam.ExamSubmitRequest;
import com.yibi.backend.entity.ExamType;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.ExamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/exams")
@RequiredArgsConstructor
public class ExamController {

    private final ExamService examService;
    private final SecurityUtils securityUtils;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ExamResponse>>> getExams(
            @RequestParam(required = false) Integer hskLevel,
            @RequestParam(required = false) ExamType examType) {
        List<ExamResponse> exams = examService.getExams(hskLevel, examType);
        return ResponseEntity.ok(ApiResponse.success(exams));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ExamDetailResponse>> getExamForTaking(@PathVariable Long id) {
        ExamDetailResponse detail = examService.getExamForTaking(id);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<ExamAttemptResponse>> submitExam(@Valid @RequestBody ExamSubmitRequest request) {
        UUID userId = securityUtils.getCurrentUserId();
        ExamAttemptResponse result = examService.submitExam(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Exam submitted successfully", result));
    }

    @GetMapping("/my-attempts")
    public ResponseEntity<ApiResponse<List<ExamAttemptResponse>>> getMyAttempts() {
        UUID userId = securityUtils.getCurrentUserId();
        List<ExamAttemptResponse> attempts = examService.getUserAttempts(userId);
        return ResponseEntity.ok(ApiResponse.success(attempts));
    }
}
