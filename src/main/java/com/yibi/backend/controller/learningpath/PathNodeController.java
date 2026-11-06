package com.yibi.backend.controller.learningpath;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.PathLearningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/** Learning one node of the path, and the test that moves a learner up a level. */
@RestController
@RequestMapping("/api/learning-path")
@RequiredArgsConstructor
public class PathNodeController {

    private final PathLearningService pathService;
    private final com.yibi.backend.service.PathMistakeService mistakeService;
    private final SecurityUtils securityUtils;

    public record CheckRequest(long seed, String questionId, String answer) { }

    public record SubmitRequest(long seed, Map<String, String> answers) { }

    @GetMapping("/nodes/{id}")
    public ResponseEntity<ApiResponse<PathLearningService.NodeDetail>> getNode(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(pathService.getNode(securityUtils.getCurrentUserId(), id)));
    }

    @PostMapping("/nodes/{id}/quiz")
    public ResponseEntity<ApiResponse<PathLearningService.Quiz>> startQuiz(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(pathService.startQuiz(securityUtils.getCurrentUserId(), id)));
    }

    @PostMapping("/nodes/{id}/quiz/check")
    public ResponseEntity<ApiResponse<PathLearningService.Checked>> check(@PathVariable long id, @RequestBody CheckRequest request) {
        return ResponseEntity.ok(ApiResponse.success(pathService.check(securityUtils.getCurrentUserId(), id,
                request.seed(), request.questionId(), request.answer())));
    }

    @PostMapping("/nodes/{id}/quiz/submit")
    public ResponseEntity<ApiResponse<PathLearningService.Outcome>> submit(@PathVariable long id, @RequestBody SubmitRequest request) {
        return ResponseEntity.ok(ApiResponse.success(pathService.submit(securityUtils.getCurrentUserId(), id,
                request.seed(), request.answers() == null ? Map.of() : request.answers())));
    }

    @GetMapping("/mistakes")
    public ResponseEntity<ApiResponse<com.yibi.backend.service.PathMistakeService.Summary>> mistakes() {
        return ResponseEntity.ok(ApiResponse.success(mistakeService.summary(securityUtils.getCurrentUserId())));
    }

    @PostMapping("/mistakes/quiz")
    public ResponseEntity<ApiResponse<PathLearningService.Quiz>> startMistakesReview() {
        return ResponseEntity.ok(ApiResponse.success(pathService.startMistakesReview(securityUtils.getCurrentUserId())));
    }

    @PostMapping("/mistakes/quiz/check")
    public ResponseEntity<ApiResponse<PathLearningService.Checked>> checkMistake(@RequestBody CheckRequest request) {
        return ResponseEntity.ok(ApiResponse.success(pathService.checkMistake(securityUtils.getCurrentUserId(), request.questionId(), request.answer())));
    }

    @PostMapping("/mistakes/quiz/submit")
    public ResponseEntity<ApiResponse<PathLearningService.Outcome>> submitMistakesReview(@RequestBody SubmitRequest request) {
        return ResponseEntity.ok(ApiResponse.success(pathService.submitMistakesReview(securityUtils.getCurrentUserId(),
                request.answers() == null ? Map.of() : request.answers())));
    }

    /** Scores one recorded line of a dialogue against its text. */
    @PostMapping(value = "/nodes/{id}/speak", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<PathLearningService.Spoken>> speak(@PathVariable long id, @RequestParam int lineIndex,
                                                                         @RequestParam org.springframework.web.multipart.MultipartFile audio) throws IOException {
        if (audio.isEmpty()) throw new com.yibi.backend.exception.BadRequestException("Chưa có bản ghi âm.");
        return ResponseEntity.ok(ApiResponse.success(pathService.speak(securityUtils.getCurrentUserId(), id, lineIndex,
                audio.getBytes(), audio.getOriginalFilename() != null ? audio.getOriginalFilename() : "speech.webm")));
    }

    @PostMapping("/levels/{level}/test")
    public ResponseEntity<ApiResponse<PathLearningService.Quiz>> startLevelTest(@PathVariable int level) {
        return ResponseEntity.ok(ApiResponse.success(pathService.startLevelTest(securityUtils.getCurrentUserId(), level)));
    }

    @PostMapping("/levels/{level}/test/check")
    public ResponseEntity<ApiResponse<PathLearningService.Checked>> checkLevelTest(@PathVariable int level, @RequestBody CheckRequest request) {
        return ResponseEntity.ok(ApiResponse.success(pathService.checkLevelTest(securityUtils.getCurrentUserId(), level,
                request.seed(), request.questionId(), request.answer())));
    }

    @PostMapping("/levels/{level}/test/submit")
    public ResponseEntity<ApiResponse<PathLearningService.Outcome>> submitLevelTest(@PathVariable int level, @RequestBody SubmitRequest request) {
        return ResponseEntity.ok(ApiResponse.success(pathService.submitLevelTest(securityUtils.getCurrentUserId(), level,
                request.seed(), request.answers() == null ? Map.of() : request.answers())));
    }
}
