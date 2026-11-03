package com.yibi.backend.controller.admin;

import com.yibi.backend.dto.admin.GeneratorRunResult;
import com.yibi.backend.dto.admin.QuestionGeneratorParams;
import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.service.QuestionGeneratorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Admin REST endpoint for the question-bank generator.
 *
 * <h3>Usage</h3>
 * <pre>
 * POST /api/admin/question-generator/run
 * Content-Type: application/json
 *
 * {
 *   "levelCode":    "1",
 *   "questionType": "PHONETICS_SELECT",
 *   "limit":        50,
 *   "mode":         "dry-run"   // or "import"
 * }
 * </pre>
 *
 * <p>In {@code dry-run} mode the response contains the full preview list but
 * nothing is written to the database.  Only {@code import} mode persists rows.</p>
 *
 * <p>Requires {@code ROLE_ADMIN}.  Tie this to your existing Spring Security
 * role hierarchy or JWT claim check.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/question-generator")
@RequiredArgsConstructor
public class QuestionGeneratorController {

    private final QuestionGeneratorService questionGeneratorService;

    /**
     * Generate (and optionally import) questions for a given HSK level and type.
     *
     * @param params generation parameters (validated)
     * @return summary + question previews
     */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<GeneratorRunResult>> run(
            @Valid @RequestBody QuestionGeneratorParams params) {

        log.info("Question generator run: level={} type={} limit={} mode={}",
                params.getLevelCode(), params.getQuestionType(),
                params.getLimit(), params.getMode());

        GeneratorRunResult result = questionGeneratorService.generate(params);

        log.info("Generator finished: generated={} skipped={} imported={} alreadyExisted={}",
                result.getGenerated(), result.getSkipped(),
                result.getImported(), result.getAlreadyExisted());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Convenience GET endpoint for a quick dry-run with query parameters,
     * e.g. for curl / browser testing.
     *
     * <pre>
     * GET /api/admin/question-generator/preview?levelCode=1&questionType=VOCAB_MEANING&limit=10
     * </pre>
     */
    @GetMapping("/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<GeneratorRunResult>> preview(
            @RequestParam(defaultValue = "1")                  String levelCode,
            @RequestParam(defaultValue = "PHONETICS_SELECT")  String questionType,
            @RequestParam(defaultValue = "10")                 int limit) {

        QuestionGeneratorParams params = new QuestionGeneratorParams();
        params.setLevelCode(levelCode);
        params.setQuestionType(questionType);
        params.setLimit(Math.min(limit, 50));
        params.setMode("dry-run");

        GeneratorRunResult result = questionGeneratorService.generate(params);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Publish draft questions in bulk.
     *
     * <pre>
     * POST /api/admin/question-generator/publish?levelCode=1&questionType=PHONETICS_SELECT
     * </pre>
     */
    @PostMapping("/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> publish(
            @RequestParam(required = false) String levelCode,
            @RequestParam(required = false) String questionType) {

        int count = questionGeneratorService.publishDraftQuestions(levelCode, questionType);
        log.info("Admin published {} questions for level={} type={}", count, levelCode, questionType);
        return ResponseEntity.ok(ApiResponse.success(java.util.Map.of(
                "publishedCount", count,
                "levelCode", levelCode != null ? levelCode : "ALL",
                "questionType", questionType != null ? questionType : "ALL"
        )));
    }
}

