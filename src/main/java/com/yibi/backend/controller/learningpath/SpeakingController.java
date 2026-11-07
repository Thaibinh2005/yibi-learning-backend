package com.yibi.backend.controller.learningpath;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.HskkExamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** HSKK practice papers: the learner records an answer per question, then hands the attempt in. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SpeakingController {

    private final HskkExamService hskk;
    private final SecurityUtils securityUtils;

    @GetMapping("/hskk/status")
    public ResponseEntity<ApiResponse<HskkExamService.Status>> status() {
        return ResponseEntity.ok(ApiResponse.success(hskk.status(securityUtils.getCurrentUserId())));
    }

    @PostMapping("/hskk/{tier}/start")
    public ResponseEntity<ApiResponse<HskkExamService.Paper>> start(@PathVariable String tier) {
        return ResponseEntity.ok(ApiResponse.success(hskk.start(securityUtils.getCurrentUserId(), tier)));
    }

    @PostMapping(value = "/hskk/attempts/{id}/answers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<HskkExamService.Answered>> answer(@PathVariable long id, @RequestParam String questionId,
                                                                        @RequestParam MultipartFile audio) throws IOException {
        if (audio.isEmpty()) throw new BadRequestException("Chưa có bản ghi âm.");
        return ResponseEntity.ok(ApiResponse.success(hskk.answer(securityUtils.getCurrentUserId(), id, questionId, audio.getBytes(),
                audio.getOriginalFilename() != null ? audio.getOriginalFilename() : "speech.webm")));
    }

    @PostMapping("/hskk/attempts/{id}/finish")
    public ResponseEntity<ApiResponse<HskkExamService.Result>> finish(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(hskk.finish(securityUtils.getCurrentUserId(), id)));
    }
}
