package com.yibi.backend.dto.listening;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yibi.backend.service.ChinesePinyinService.Token;
import com.yibi.backend.service.SpeechTranscriptionClient.CharTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Listening lessons for dictation and shadowing. A lesson is the lines a contributor wrote over a video (a YouTube
 * video credited to its channel, an uploaded file, or none for typed text); it is private until an admin publishes it.
 */
public final class ListeningDtos {

    private ListeningDtos() { }

    public enum Source { YOUTUBE, UPLOAD, MANUAL }

    public enum Visibility { PRIVATE, PENDING_REVIEW, PUBLISHED, REJECTED, HIDDEN }

    /**
     * One sentence as stored in listening_lessons.segments. Times are seconds and may be missing while the learner
     * is still timing typed text. {@code viStale} marks a translation made before the sentence was edited.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LearnerSegment(String text, Double start, Double end, String pinyin, String vi, Boolean viStale,
                                 List<Token> words, List<String> flags, List<CharTime> timing) { }

    public record SegmentInput(@NotNull @Size(max = 500) String text, Double start, Double end,
                               @Size(max = 2000) String vi, Boolean viStale, @Size(max = 600) List<CharTime> timing) { }

    /** YOUTUBE reads the video's Chinese subtitles; MANUAL takes typed text, one sentence per line, with an optional link. */
    public record CreateVideoRequest(@Size(max = 200) String title, @NotNull Source source,
                                     @Size(max = 300) String youtubeUrl, @Size(max = 100_000) String text,
                                     boolean confirmed) { }

    public record SaveSegmentsRequest(@Size(max = 200) String title,
                                      @NotNull @Size(max = 3000) List<@Valid SegmentInput> segments) { }

    public record Usage(int videosAdded, int maxVideos, int segmentsTranslated, int maxSegments, int maxMediaMb,
                        int maxMediaMinutes) { }

    public record CategoryRef(long id, String name, String slug) { }

    /**
     * A lesson on a card. {@code mine}: the viewer contributed it; {@code saved}: the viewer saved someone else's.
     * {@code hskLevel} is the admin's level, else the estimate from the words used.
     */
    public record VideoSummary(long id, String title, Source source, String videoId, boolean hasMedia, String status,
                               String statusMessage, String translationStatus, int segmentCount, int translatedCount,
                               LocalDateTime updatedAt, Double durationSeconds, String hskLevel, Visibility visibility,
                               boolean mine, boolean saved, String channelName, String contributorName,
                               int practiceCount) { }

    public record VideoList(List<VideoSummary> videos, Usage usage) { }

    /**
     * A lesson opened for practice or editing. {@code editable}: the viewer may change the lines now (their own lesson
     * while private or rejected, or any lesson for an admin); {@code canSubmit}: it may be sent to the community.
     */
    public record VideoDetail(long id, String title, Source source, String videoId, boolean hasMedia, String mediaType,
                              String status, String statusMessage, String translationStatus, String translationMessage,
                              List<LearnerSegment> segments, LocalDateTime updatedAt, Visibility visibility,
                              boolean mine, boolean editable, boolean saved, String channelName, String channelUrl,
                              String contributorName, String description, String usageRights, String reviewNote,
                              String hskLevel, List<CategoryRef> categories) { }

    public record MediaUrl(String url, long expiresAt) { }

    public record ShadowingResult(double accuracy, String transcript, boolean passed) { }

    // ── Community ─────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Sends a finished lesson for review. {@code usageRights} is asked only for an uploaded file (OWN,
     * CREATIVE_COMMONS or PERMISSION); a YouTube video is embedded and typed text is the contributor's own.
     */
    public record SubmitRequest(@NotNull @Size(min = 1, max = 3, message = "Hãy chọn từ 1 đến 3 danh mục.") List<Long> categoryIds,
                                @Size(max = 500) String description, String usageRights,
                                @Size(max = 500) String rightsNote, boolean confirmed) { }

    public record Category(long id, String name, String slug, String description, int sortOrder, boolean active,
                           int lessonCount) { }

    public record LibrarySection(Category category, List<VideoSummary> videos, int total) { }

    /** The public page: system rows (newest, most practised) and one row per active category with lessons. */
    public record LibraryHome(List<VideoSummary> newest, List<VideoSummary> popular, List<LibrarySection> sections) { }

    public record CategoryPage(Category category, List<VideoSummary> videos, int page, int totalPages, long total) { }

    // ── Admin ─────────────────────────────────────────────────────────────────────────────────────────────────────

    public record CategoryRequest(@NotBlank @Size(max = 80) String name, @Size(max = 80) String slug,
                                  @Size(max = 300) String description, Boolean active) { }

    public record ReorderRequest(@NotNull @Size(max = 500) List<Long> ids) { }

    /** Approving: the categories the admin settles on (1–3), and optional fixes to the shown details. */
    public record ApproveRequest(@NotNull @Size(min = 1, max = 3, message = "Hãy chọn từ 1 đến 3 danh mục.") List<Long> categoryIds, @Size(max = 200) String title,
                                 @Size(max = 500) String description, String hskLevel, @Size(max = 500) String note) { }

    public record RejectRequest(@NotBlank @Size(max = 500) String note) { }

    public record AdminLessonRow(long id, String title, Source source, String videoId, boolean hasMedia,
                                 Visibility visibility, String status, String translationStatus, int segmentCount,
                                 int translatedCount, String hskLevel, String estimatedLevel, Double durationSeconds,
                                 String contributorName, String channelName, String usageRights, String rightsNote,
                                 String description, String reviewNote, List<CategoryRef> categories,
                                 LocalDateTime submittedAt, LocalDateTime publishedAt, int practiceCount, int saveCount) { }

    public record AdminLessonPage(List<AdminLessonRow> lessons, int page, int totalPages, long total, int pendingCount) { }
}
