package com.yibi.backend.controller.admin;

import com.yibi.backend.dto.admin.QuestionBankItemAdminDto;
import com.yibi.backend.dto.admin.QuestionBankPageResponse;
import com.yibi.backend.dto.admin.QuestionBankStatsResponse;
import com.yibi.backend.dto.admin.QuestionUpdateRequest;
import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.service.QuestionBankAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/admin/question-bank")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class QuestionBankAdminController {

    private final QuestionBankAdminService questionBankAdminService;

    @GetMapping("/items")
    public ResponseEntity<ApiResponse<QuestionBankPageResponse>> getQuestions(
            @RequestParam(required = false) String levelCode,
            @RequestParam(required = false) String skillKey,
            @RequestParam(required = false) String questionType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {

        QuestionBankPageResponse response = questionBankAdminService.getQuestions(
                levelCode, skillKey, questionType, status, search, page, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<ApiResponse<QuestionBankItemAdminDto>> getQuestionById(@PathVariable Long id) {
        QuestionBankItemAdminDto item = questionBankAdminService.getQuestionById(id);
        return ResponseEntity.ok(ApiResponse.success(item));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<QuestionBankStatsResponse>> getStats() {
        QuestionBankStatsResponse stats = questionBankAdminService.getStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @PostMapping("/items/{id}/publish")
    public ResponseEntity<ApiResponse<String>> publishQuestion(@PathVariable Long id) {
        questionBankAdminService.publishQuestion(id);
        return ResponseEntity.ok(ApiResponse.success("Question published successfully"));
    }

    @PutMapping("/items/{id}")
    public ResponseEntity<ApiResponse<QuestionBankItemAdminDto>> updateQuestion(
            @PathVariable Long id,
            @Valid @RequestBody QuestionUpdateRequest request) {
        QuestionBankItemAdminDto updated = questionBankAdminService.updateQuestion(id, request);
        return ResponseEntity.ok(ApiResponse.success("Question updated successfully", updated));
    }

    @DeleteMapping("/items/{id}")
    public ResponseEntity<ApiResponse<String>> deleteQuestion(@PathVariable Long id) {
        questionBankAdminService.deleteQuestion(id);
        return ResponseEntity.ok(ApiResponse.success("Question deleted successfully"));
    }
}

