package com.yibi.backend.controller.listening;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.listening.ListeningDtos.CreateVideoRequest;
import com.yibi.backend.dto.listening.ListeningDtos.MediaUrl;
import com.yibi.backend.dto.listening.ListeningDtos.SaveSegmentsRequest;
import com.yibi.backend.dto.listening.ListeningDtos.ShadowingResult;
import com.yibi.backend.dto.listening.ListeningDtos.SubmitRequest;
import com.yibi.backend.dto.listening.ListeningDtos.VideoDetail;
import com.yibi.backend.dto.listening.ListeningDtos.VideoList;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.LearnerVideoService;
import com.yibi.backend.service.ListeningLibraryService;
import com.yibi.backend.service.ListeningViewer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

/**
 * Listening lessons for a signed-in learner: their own contributions (add, edit, finish, submit to the community) and
 * any published lesson (open, practise, save). {@code GET /media/{id}} is opened by the player with a signed, expiring
 * link instead of the login header. The public library is in {@link ListeningLibraryController}.
 */
@RestController
@RequestMapping("/api/listening")
@RequiredArgsConstructor
public class ListeningController {

    private final LearnerVideoService videos;
    private final ListeningLibraryService library;
    private final SecurityUtils securityUtils;

    private ListeningViewer viewer() {
        return ListeningViewer.of(securityUtils.getCurrentUser());
    }

    @GetMapping("/videos")
    public ResponseEntity<ApiResponse<VideoList>> list() {
        return ResponseEntity.ok(ApiResponse.success(videos.list(viewer())));
    }

    @PostMapping("/videos")
    public ResponseEntity<ApiResponse<VideoDetail>> create(@Valid @RequestBody CreateVideoRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã thêm video", videos.create(viewer(), request)));
    }

    /** {@code text} is optional: with it the file is timed against the typed sentences, without it Whisper transcribes. */
    @PostMapping(value = "/videos/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<VideoDetail>> upload(@RequestParam MultipartFile file,
                                                           @RequestParam(required = false) String title,
                                                           @RequestParam(required = false) String text,
                                                           @RequestParam(defaultValue = "false") boolean confirmed) throws IOException {
        return ResponseEntity.ok(ApiResponse.success("Đã tải file lên",
                videos.upload(viewer(), file, title, text, confirmed)));
    }

    @GetMapping("/videos/{id}")
    public ResponseEntity<ApiResponse<VideoDetail>> get(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(videos.get(viewer(), id)));
    }

    @PutMapping("/videos/{id}/segments")
    public ResponseEntity<ApiResponse<VideoDetail>> save(@PathVariable long id, @Valid @RequestBody SaveSegmentsRequest request) {
        return ResponseEntity.ok(ApiResponse.success(videos.saveSegments(viewer(), id, request)));
    }

    @PostMapping("/videos/{id}/finish")
    public ResponseEntity<ApiResponse<VideoDetail>> finish(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(videos.finish(viewer(), id)));
    }

    @PostMapping("/videos/{id}/translate")
    public ResponseEntity<ApiResponse<VideoDetail>> translate(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(videos.translate(viewer(), id)));
    }

    @PostMapping("/videos/{id}/retry")
    public ResponseEntity<ApiResponse<VideoDetail>> retry(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(videos.retryTranscription(viewer(), id)));
    }

    @DeleteMapping("/videos/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable long id) {
        videos.delete(viewer(), id);
        return ResponseEntity.ok(ApiResponse.success("Đã xoá video", null));
    }

    @PostMapping("/videos/{id}/submit")
    public ResponseEntity<ApiResponse<VideoDetail>> submit(@PathVariable long id, @Valid @RequestBody SubmitRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã gửi bài lên cộng đồng", videos.submit(viewer(), id, request)));
    }

    @PostMapping("/videos/{id}/withdraw")
    public ResponseEntity<ApiResponse<VideoDetail>> withdraw(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success("Đã rút bài về", videos.withdraw(viewer(), id)));
    }

    /** Saves someone else's published lesson into "Video của tôi". */
    @PostMapping("/videos/{id}/save")
    public ResponseEntity<ApiResponse<Void>> saveToShelf(@PathVariable long id) {
        library.save(viewer(), id);
        return ResponseEntity.ok(ApiResponse.success("Đã lưu vào Video của tôi", null));
    }

    @DeleteMapping("/videos/{id}/save")
    public ResponseEntity<ApiResponse<Void>> unsave(@PathVariable long id) {
        library.unsave(viewer(), id);
        return ResponseEntity.ok(ApiResponse.success("Đã bỏ lưu", null));
    }

    /** Called when the practice page opens; counted once per learner per day. */
    @PostMapping("/videos/{id}/practised")
    public ResponseEntity<ApiResponse<Void>> practised(@PathVariable long id) {
        library.practised(viewer(), id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/videos/{id}/media-url")
    public ResponseEntity<ApiResponse<MediaUrl>> mediaUrl(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(videos.mediaUrl(viewer(), id)));
    }

    /** Streams the file; Spring answers Range requests itself, so the player can seek. */
    @GetMapping("/media/{id}")
    public ResponseEntity<Resource> media(@PathVariable long id, @RequestParam long exp, @RequestParam String sig) throws IOException {
        LearnerVideoService.OpenedMedia media = videos.openMedia(id, exp, sig);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(media.type()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(media.resource());
    }

    @PostMapping(value = "/videos/{id}/segments/{index}/shadowing", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ShadowingResult>> shadowing(@PathVariable long id, @PathVariable int index,
                                                                  @RequestParam MultipartFile audio) throws IOException {
        return ResponseEntity.ok(ApiResponse.success(videos.shadowing(viewer(), id, index, audio)));
    }
}
