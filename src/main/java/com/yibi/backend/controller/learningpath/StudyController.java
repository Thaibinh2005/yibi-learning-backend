package com.yibi.backend.controller.learningpath;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.MockExamService;
import com.yibi.backend.service.StudyStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Study statistics, and the practice exam with its on-screen pinyin keyboard. */
@RestController
@RequestMapping("/api/learning-path")
@RequiredArgsConstructor
public class StudyController {

    private final StudyStatsService statsService;
    private final MockExamService examService;
    private final SecurityUtils securityUtils;

    public record ActivityRequest(int seconds) { }

    public record ExamSubmitRequest(long seed, Map<String, String> answers) { }

    @PostMapping("/activity")
    public ResponseEntity<ApiResponse<String>> recordActivity(@RequestBody ActivityRequest request) {
        statsService.recordActivity(securityUtils.getCurrentUserId(), request.seconds());
        return ResponseEntity.ok(ApiResponse.success("recorded"));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<StudyStatsService.Stats>> getStats() {
        return ResponseEntity.ok(ApiResponse.success(statsService.get(securityUtils.getCurrentUserId())));
    }

    @GetMapping("/ime")
    public ResponseEntity<ApiResponse<List<String>>> pinyinCandidates(@RequestParam("q") String typed) {
        return ResponseEntity.ok(ApiResponse.success(examService.pinyinCandidates(typed)));
    }

    @PostMapping("/mock-exam/{level}")
    public ResponseEntity<ApiResponse<MockExamService.Paper>> startExam(@PathVariable int level) {
        return ResponseEntity.ok(ApiResponse.success(examService.start(securityUtils.getCurrentUserId(), level)));
    }

    @PostMapping("/mock-exam/{level}/submit")
    public ResponseEntity<ApiResponse<MockExamService.Result>> submitExam(@PathVariable int level, @RequestBody ExamSubmitRequest request) {
        return ResponseEntity.ok(ApiResponse.success(examService.submit(securityUtils.getCurrentUserId(), level,
                request.seed(), request.answers() == null ? Map.of() : request.answers())));
    }
}
