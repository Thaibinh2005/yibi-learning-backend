package com.yibi.backend.controller.admin;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.video.VideoTranscriptDtos.SaveTranscriptRequest;
import com.yibi.backend.dto.video.VideoTranscriptDtos.YoutubeLanguages;
import com.yibi.backend.dto.video.VideoTranscriptDtos.YoutubeTranscript;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.VideoTranscriptService;
import com.yibi.backend.service.YoutubeTranscriptClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin tool for the subtitles of dictation and shadowing videos: read the Chinese tracks of a YouTube video,
 * review and edit the preview, then save it as a draft lesson. Reading YouTube writes nothing.
 */
@RestController
@RequestMapping("/api/admin/video-transcripts")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class VideoTranscriptAdminController {

    private final YoutubeTranscriptClient youtube;
    private final VideoTranscriptService transcripts;
    private final SecurityUtils securityUtils;

    /** @param video a YouTube link or 11-character id */
    @GetMapping("/youtube/languages")
    public ResponseEntity<ApiResponse<YoutubeLanguages>> languages(@RequestParam String video) {
        return ResponseEntity.ok(ApiResponse.success(youtube.languages(video)));
    }

    @GetMapping("/youtube/transcript")
    public ResponseEntity<ApiResponse<YoutubeTranscript>> preview(@RequestParam String video,
                                                                 @RequestParam(required = false) String language,
                                                                 @RequestParam(required = false) Boolean generated) {
        return ResponseEntity.ok(ApiResponse.success(youtube.transcript(video, language, generated)));
    }

    @PostMapping
    /** Creates a draft lesson from the reviewed subtitles; the admin finishes and publishes it as a lesson. */
    public ResponseEntity<ApiResponse<VideoTranscriptService.CreatedLesson>> save(@Valid @RequestBody SaveTranscriptRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã tạo bài luyện nháp",
                transcripts.save(securityUtils.getCurrentUserId(), request)));
    }
}
