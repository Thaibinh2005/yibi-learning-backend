package com.yibi.backend.service;

import com.yibi.backend.dto.listening.ListeningDtos.CreateVideoRequest;
import com.yibi.backend.dto.listening.ListeningDtos.LearnerSegment;
import com.yibi.backend.dto.listening.ListeningDtos.MediaUrl;
import com.yibi.backend.dto.listening.ListeningDtos.SaveSegmentsRequest;
import com.yibi.backend.dto.listening.ListeningDtos.SegmentInput;
import com.yibi.backend.dto.listening.ListeningDtos.ShadowingResult;
import com.yibi.backend.dto.listening.ListeningDtos.Source;
import com.yibi.backend.dto.listening.ListeningDtos.SubmitRequest;
import com.yibi.backend.dto.listening.ListeningDtos.Usage;
import com.yibi.backend.dto.listening.ListeningDtos.VideoDetail;
import com.yibi.backend.dto.listening.ListeningDtos.VideoList;
import com.yibi.backend.dto.listening.ListeningDtos.VideoSummary;
import com.yibi.backend.dto.listening.ListeningDtos.Visibility;
import com.yibi.backend.dto.video.VideoTranscriptDtos.TranscriptSegment;
import com.yibi.backend.dto.video.VideoTranscriptDtos.YoutubeTranscript;
import com.yibi.backend.exception.AppException;
import com.yibi.backend.service.SpeechTranscriptionClient.HeardSegment;
import com.yibi.backend.storage.MediaStorage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listening lessons from the contributor's side: adding a YouTube video (its Chinese subtitles), an audio/video file
 * transcribed by Whisper, or typed text; editing the lines; finishing (pinyin at once from the dictionary, Vietnamese
 * from Gemini in the background, cached per sentence); and sending the lesson to the community for review.
 *
 * <p>The video is never the contributor's: a YouTube video is a shared {@code media_sources} row credited to its
 * channel. Status: PROCESSING (Whisper) → DRAFT (editing) → READY. Visibility: PRIVATE → PENDING_REVIEW → PUBLISHED
 * (or REJECTED). Anyone signed in may open a published lesson; only its contributor edits it while it is private or
 * rejected, and an admin may edit any lesson.</p>
 */
@Slf4j
@Service
public class LearnerVideoService {

    static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    static final Set<String> MEDIA_EXTENSIONS = Set.of("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac",
            "webm", "mp4", "m4v", "mov");
    static final Set<String> SHAREABLE_UPLOAD_RIGHTS = Set.of("OWN", "CREATIVE_COMMONS", "PERMISSION");
    static final Set<String> LEVELS = Set.of("1", "2", "3", "4", "5", "6", "7-9");
    private static final long MEDIA_URL_SECONDS = 6 * 3600;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ListeningLessonStore store;
    private final MediaStorage storage;
    private final ChinesePinyinService pinyin;
    private final SegmentTranslationService translations;
    private final YoutubeTranscriptClient youtube;
    private final SpeechTranscriptionClient transcriber;
    private final SpeechScoringClient scorer;
    private final YoutubeTitleClient youtubeInfo;
    private final int maxVideosPerDay;
    private final int maxSegmentsPerDay;
    private final int maxMediaMb;
    private final int maxMediaMinutes;
    private final double passAccuracy;
    /** Whisper is CPU-heavy: one file at a time. Translation waits on Gemini, so two lessons may run together. */
    private final ExecutorService transcribeJobs = Executors.newSingleThreadExecutor(r -> daemon(r, "transcribe"));
    private final ExecutorService translateJobs = Executors.newFixedThreadPool(2, r -> daemon(r, "translate"));
    /** Signs media links; a new key at each start only means open pages ask for a new link. */
    private final byte[] mediaKey = new byte[32];

    public LearnerVideoService(JdbcTemplate jdbc, TransactionTemplate tx, ListeningLessonStore store, MediaStorage storage,
                               ChinesePinyinService pinyin, SegmentTranslationService translations,
                               YoutubeTranscriptClient youtube, SpeechTranscriptionClient transcriber,
                               SpeechScoringClient scorer, YoutubeTitleClient youtubeInfo,
                               @Value("${app.listening.max-videos-per-day:5}") int maxVideosPerDay,
                               @Value("${app.listening.max-translated-segments-per-day:300}") int maxSegmentsPerDay,
                               @Value("${app.listening.max-media-mb:100}") int maxMediaMb,
                               @Value("${app.listening.max-media-minutes:20}") int maxMediaMinutes,
                               @Value("${app.speech.pass-accuracy:0.7}") double passAccuracy) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.store = store;
        this.storage = storage;
        this.pinyin = pinyin;
        this.translations = translations;
        this.youtube = youtube;
        this.transcriber = transcriber;
        this.scorer = scorer;
        this.youtubeInfo = youtubeInfo;
        this.maxVideosPerDay = maxVideosPerDay;
        this.maxSegmentsPerDay = maxSegmentsPerDay;
        this.maxMediaMb = maxMediaMb;
        this.maxMediaMinutes = maxMediaMinutes;
        this.passAccuracy = passAccuracy;
        new SecureRandom().nextBytes(mediaKey);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, "listening-" + name);
        t.setDaemon(true);
        return t;
    }

    @PreDestroy
    void stop() {
        transcribeJobs.shutdownNow();
        translateJobs.shutdownNow();
    }

    /**
     * Jobs do not survive a restart: say so, so the contributor can press "Thử lại" / "Dịch tiếp". Runs while the
     * context starts (after Flyway, before the server takes requests), so it can never catch a job of this instance.
     */
    @PostConstruct
    public void recoverInterruptedJobs() {
        int a = jdbc.update("""
                UPDATE listening_lessons SET status = 'FAILED',
                       status_message = 'Máy chủ khởi động lại khi đang nhận dạng. Hãy bấm Thử lại.'
                WHERE status = 'PROCESSING'
                """);
        int b = jdbc.update("""
                UPDATE listening_lessons SET translation_status = 'FAILED',
                       translation_message = 'Máy chủ khởi động lại khi đang dịch. Hãy bấm Dịch tiếp.'
                WHERE translation_status = 'RUNNING'
                """);
        if (a + b > 0) log.info("Marked {} transcriptions and {} translations as interrupted", a, b);
    }

    // ── The row behind a lesson ─────────────────────────────────────────────────────────────────────────────────

    /** Everything about one lesson that the access rules and the pages need. */
    record Lesson(long id, String title, Source source, UUID contributor, Visibility visibility, String status,
                  String statusMessage, String translationStatus, String translationMessage, Long mediaSourceId,
                  String provider, String videoId, String mediaPath, String mediaType, String channelName,
                  String channelUrl, String contributorName, String description, String usageRights, String reviewNote,
                  String hskLevel, int segmentCount, int translatedCount, List<LearnerSegment> segments,
                  java.time.LocalDateTime updatedAt, boolean editorial) {

        boolean playable() {
            return videoId != null || mediaPath != null;
        }
    }

    Lesson load(long id) {
        List<Lesson> rows = jdbc.query("""
                SELECT l.id, l.title, l.source_type, l.contributor_id, l.visibility, l.status, l.status_message,
                       l.translation_status, l.translation_message, l.media_source_id, m.provider, m.external_id,
                       m.media_path, m.media_type, m.channel_name, m.channel_url,
                       CASE WHEN l.editorial THEN ? ELSE u.username END AS username, l.description,
                       l.usage_rights, l.review_note, COALESCE(l.hsk_level, l.est_level) AS level, l.segment_count,
                       l.translated_count, l.segments::text AS segments, l.updated_at, l.editorial
                FROM listening_lessons l
                LEFT JOIN media_sources m ON m.id = l.media_source_id
                LEFT JOIN users u ON u.id = l.contributor_id
                WHERE l.id = ?
                """, (rs, i) -> new Lesson(rs.getLong("id"), rs.getString("title"), Source.valueOf(rs.getString("source_type")),
                rs.getObject("contributor_id", UUID.class), Visibility.valueOf(rs.getString("visibility")),
                rs.getString("status"), rs.getString("status_message"), rs.getString("translation_status"),
                rs.getString("translation_message"), (Long) rs.getObject("media_source_id"), rs.getString("provider"),
                rs.getString("external_id"), rs.getString("media_path"), rs.getString("media_type"),
                rs.getString("channel_name"), rs.getString("channel_url"), rs.getString("username"),
                rs.getString("description"), rs.getString("usage_rights"), rs.getString("review_note"),
                rs.getString("level"), rs.getInt("segment_count"), rs.getInt("translated_count"),
                store.read(rs.getString("segments")), ListeningLessonStore.local(rs.getTimestamp("updated_at")),
                rs.getBoolean("editorial")), ListeningLessonStore.TEAM, id);
        if (rows.isEmpty()) throw notFound();
        return rows.get(0);
    }

    static boolean readable(ListeningViewer viewer, Lesson lesson) {
        return viewer.admin() || viewer.is(lesson.contributor())
                || (lesson.visibility() == Visibility.PUBLISHED && "READY".equals(lesson.status()));
    }

    static boolean editable(ListeningViewer viewer, Lesson lesson) {
        return viewer.admin() || (viewer.is(lesson.contributor())
                && (lesson.visibility() == Visibility.PRIVATE || lesson.visibility() == Visibility.REJECTED));
    }

    private Lesson readableLesson(ListeningViewer viewer, long id) {
        Lesson lesson = load(id);
        if (!readable(viewer, lesson)) throw notFound();
        return lesson;
    }

    private Lesson editableLesson(ListeningViewer viewer, long id) {
        Lesson lesson = readableLesson(viewer, id);
        if (editable(viewer, lesson)) return lesson;
        if (!viewer.is(lesson.contributor())) {
            throw new AppException("Bạn chỉ sửa được bài luyện do mình đóng góp.", HttpStatus.FORBIDDEN);
        }
        throw new AppException(lesson.visibility() == Visibility.PENDING_REVIEW
                ? "Bài đang chờ duyệt. Hãy rút bài về để sửa."
                : "Bài đã công khai trong cộng đồng nên chỉ admin sửa được. Hãy báo admin nếu cần chỉnh.", HttpStatus.CONFLICT);
    }

    private VideoDetail detail(ListeningViewer viewer, Lesson l) {
        boolean saved = viewer.id() != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_saved_lessons WHERE user_id = ? AND lesson_id = ?)", Boolean.class,
                viewer.id(), l.id()));
        return new VideoDetail(l.id(), l.title(), l.source(), l.videoId(), l.mediaPath() != null, l.mediaType(),
                l.status(), l.statusMessage(), l.translationStatus(), l.translationMessage(), l.segments(), l.updatedAt(),
                l.visibility(), viewer.is(l.contributor()), editable(viewer, l), saved, l.channelName(), l.channelUrl(),
                l.contributorName(), l.description(), l.usageRights(), viewer.is(l.contributor()) || viewer.admin()
                ? l.reviewNote() : null, l.hskLevel(), store.categoriesOf(l.id()), l.editorial());
    }

    // ── Reading ──────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * "Video của tôi": the lessons the viewer contributed and the published ones they saved. Lessons an admin added
     * for the community belong to the team and are managed in the admin library tab instead.
     */
    public VideoList list(ListeningViewer viewer) {
        List<VideoSummary> videos = jdbc.query("SELECT " + ListeningLessonStore.SUMMARY_COLUMNS
                        + ListeningLessonStore.SUMMARY_FROM + """
                        WHERE (l.contributor_id = ? AND NOT l.editorial)
                           OR (sv.user_id IS NOT NULL AND l.visibility = 'PUBLISHED')
                        ORDER BY GREATEST(l.updated_at, COALESCE(sv.saved_at, l.updated_at)) DESC
                        """, store.summaries(viewer), viewer.idOrNobody(), viewer.idOrNobody());
        return new VideoList(videos, usage(viewer.id()));
    }

    public Usage usage(UUID user) {
        int[] used = todayUsage(user);
        return new Usage(used[0], maxVideosPerDay, used[1], maxSegmentsPerDay, maxMediaMb, maxMediaMinutes);
    }

    public VideoDetail get(ListeningViewer viewer, long id) {
        return detail(viewer, readableLesson(viewer, id));
    }

    // ── Adding material ──────────────────────────────────────────────────────────────────────────────────────────

    /** A YouTube video (its Chinese subtitles) or typed text, optionally played against a YouTube link. */
    public VideoDetail create(ListeningViewer viewer, CreateVideoRequest request) {
        if (!request.confirmed()) throw bad("Hãy xác nhận trước khi thêm video.");
        String videoId = null;
        String language = "zh-Hans";
        boolean generated = false;
        List<LearnerSegment> segments;
        if (request.source() == Source.YOUTUBE) {
            if (blank(request.youtubeUrl())) throw bad("Hãy dán link YouTube.");
            checkDailyVideos(viewer);
            YoutubeTranscript transcript = youtube.transcript(request.youtubeUrl(), null, null);
            videoId = transcript.videoId();
            language = transcript.languageCode();
            generated = transcript.isGenerated();
            segments = new ArrayList<>();
            for (TranscriptSegment s : transcript.segments()) {
                segments.add(new LearnerSegment(s.text(), s.start(), s.end(), null, null, null, null, null, null));
            }
        } else if (request.source() == Source.MANUAL) {
            segments = linesToSegments(request.text());
            if (!blank(request.youtubeUrl())) videoId = youtubeId(request.youtubeUrl());
            checkDailyVideos(viewer);
        } else {
            throw bad("File âm thanh/video được tải lên ở mục Tải file.");
        }
        Long sourceId = videoId == null ? null : youtubeSource(videoId);
        String title = !blank(request.title()) ? request.title().trim()
                : sourceId != null ? sourceTitle(sourceId) : null;
        if (blank(title)) title = request.source() == Source.MANUAL ? titleFromText(segments) : "Video YouTube " + videoId;
        String rights = videoId != null ? "YOUTUBE_EMBED" : "OWN";
        long id = insert(viewer.id(), title, request.source(), sourceId, language, generated, "DRAFT", rights,
                withPinyin(segments), viewer.admin());
        countVideo(viewer);
        return get(viewer, id);
    }

    /** An uploaded audio/video file: transcribed by Whisper in the background, or timed against the typed text. */
    public VideoDetail upload(ListeningViewer viewer, MultipartFile file, String title, String text, boolean confirmed)
            throws IOException {
        if (!confirmed) throw bad("Hãy xác nhận trước khi tải file lên.");
        if (file == null || file.isEmpty()) throw bad("Hãy chọn một file âm thanh hoặc video.");
        if (blank(title)) title = titleFromFileName(file.getOriginalFilename());
        if (title.length() > 200) throw bad("Tên video tối đa 200 ký tự.");
        if (file.getSize() > maxMediaMb * 1024L * 1024L) throw bad("File lớn hơn " + maxMediaMb + " MB.");
        String extension = extension(file.getOriginalFilename());
        if (!MEDIA_EXTENSIONS.contains(extension)) {
            throw bad("Định dạng chưa hỗ trợ. Hãy dùng mp3, m4a, wav, ogg, webm, mp4 hoặc mov.");
        }
        byte[] head = new byte[16];
        try (InputStream in = file.getInputStream()) {
            int n = in.readNBytes(head, 0, head.length);
            if (!looksLikeMedia(head, n)) throw bad("File không phải âm thanh hoặc video hợp lệ.");
        }
        List<LearnerSegment> typed = blank(text) ? null : linesToSegments(text);
        checkDailyVideos(viewer);
        String key;
        try (InputStream in = file.getInputStream()) {
            key = storage.store(in, extension);
        }
        long id;
        try {
            Long sourceId = jdbc.queryForObject("""
                    INSERT INTO media_sources (provider, title, media_path, media_type, media_bytes, uploaded_by)
                    VALUES ('UPLOAD', ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, title.trim(), key, mediaType(extension), file.getSize(), viewer.id());
            id = insert(viewer.id(), title, typed == null ? Source.UPLOAD : Source.MANUAL, sourceId, "zh-Hans", false,
                    typed == null ? "PROCESSING" : "DRAFT", "PERSONAL_STUDY", typed == null ? List.of() : withPinyin(typed),
                    viewer.admin());
        } catch (RuntimeException ex) {
            storage.delete(key);
            throw ex;
        }
        countVideo(viewer);
        if (typed == null) transcribeJobs.submit(() -> transcribe(id));
        return get(viewer, id);
    }

    /**
     * The admin subtitle tool: a reviewed YouTube transcript becomes a draft lesson of the admin, opened next in the
     * editor (which adds pinyin on save) and published from the library tab.
     */
    public long createFromReviewedTranscript(UUID adminId, String title, String videoId, String language, boolean generated,
                                             String hskLevel, String rightsNote, List<TranscriptSegment> transcript) {
        List<LearnerSegment> segments = new ArrayList<>();
        for (TranscriptSegment s : transcript) {
            segments.add(new LearnerSegment(s.text(), s.start(), s.end(), null, null, null, null, null, null));
        }
        long id = insert(adminId, title, Source.YOUTUBE, youtubeSource(videoId), language, generated, "DRAFT",
                "YOUTUBE_EMBED", withPinyin(segments), true);
        jdbc.update("UPDATE listening_lessons SET hsk_level = ?, rights_note = ? WHERE id = ?",
                hskLevel != null && LEVELS.contains(hskLevel) ? hskLevel : null, blank(rightsNote) ? null : rightsNote.trim(), id);
        return id;
    }

    public VideoDetail retryTranscription(ListeningViewer viewer, long id) {
        Lesson lesson = editableLesson(viewer, id);
        int changed = jdbc.update("""
                UPDATE listening_lessons SET status = 'PROCESSING', status_message = NULL, updated_at = now()
                WHERE id = ? AND source_type = 'UPLOAD' AND status = 'FAILED'
                """, lesson.id());
        if (changed == 0 || lesson.mediaPath() == null) throw bad("Video này không cần nhận dạng lại.");
        transcribeJobs.submit(() -> transcribe(id));
        return get(viewer, id);
    }

    void transcribe(long id) {
        try {
            String key = jdbc.queryForObject("""
                    SELECT m.media_path FROM listening_lessons l JOIN media_sources m ON m.id = l.media_source_id
                    WHERE l.id = ?
                    """, String.class, id);
            Resource media = storage.load(key);
            List<HeardSegment> heard = transcriber.transcribe(media, key.substring(key.lastIndexOf('/') + 1), maxMediaMinutes * 60);
            List<LearnerSegment> segments = new ArrayList<>();
            for (HeardSegment h : heard) {
                segments.add(new LearnerSegment(h.text(), h.start(), h.end(), null, null, null, null, null, h.timing()));
            }
            List<LearnerSegment> analysed = withPinyin(segments);
            tx.executeWithoutResult(status -> {
                String current = jdbc.queryForObject("SELECT status FROM listening_lessons WHERE id = ? FOR UPDATE", String.class, id);
                if (!"PROCESSING".equals(current)) return;
                store.writeSegments(id, analysed);
                jdbc.update("UPDATE listening_lessons SET status = 'DRAFT', status_message = NULL, updated_at = now() WHERE id = ?", id);
            });
        } catch (Exception ex) {
            String message = ex instanceof AppException ? ex.getMessage() : "Không nhận dạng được file. Hãy thử lại.";
            log.warn("Transcription of lesson {} failed: {}", id, ex.getMessage());
            jdbc.update("UPDATE listening_lessons SET status = 'FAILED', status_message = ?, updated_at = now() WHERE id = ?",
                    abbreviate(message), id);
        }
    }

    // ── Editing ──────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Replaces the lines (the editor saves the whole list after each change). Pinyin is written again, and a line still
     * without a fresh translation takes one from the cache when the same sentence was translated before.
     */
    public VideoDetail saveSegments(ListeningViewer viewer, long id, SaveSegmentsRequest request) {
        Lesson current = editableLesson(viewer, id);
        if ("PROCESSING".equals(current.status())) throw new AppException("Video đang được nhận dạng, hãy đợi xong.", HttpStatus.CONFLICT);
        List<LearnerSegment> segments = new ArrayList<>();
        for (int i = 0; i < request.segments().size(); i++) {
            SegmentInput s = request.segments().get(i);
            String text = s.text().trim().replaceAll("\\s+", " ");
            if (text.isEmpty()) continue;
            Double start = round(s.start());
            Double end = round(s.end());
            if ((start != null && start < 0) || (start != null && end != null && end <= start)) {
                throw bad("Đoạn " + (i + 1) + " có thời gian không hợp lệ (kết thúc phải sau bắt đầu).");
            }
            String vi = blank(s.vi()) ? null : s.vi().trim();
            segments.add(new LearnerSegment(text, start, end, null, vi, vi != null && Boolean.TRUE.equals(s.viStale()),
                    null, null, s.timing()));
        }
        // Autosave sends every line each time: unchanged lines keep their analysis, only new or edited ones are redone.
        List<LearnerSegment> analysed = fillFromCache(withPinyin(segments, current.segments()));
        String title = blank(request.title()) ? current.title() : request.title().trim();
        tx.executeWithoutResult(status -> {
            store.writeSegments(id, analysed);
            jdbc.update("UPDATE listening_lessons SET title = ?, updated_at = now() WHERE id = ?", title, id);
        });
        return get(viewer, id);
    }

    /** Ends editing: the lesson becomes practice material and its lines go to translation. */
    public VideoDetail finish(ListeningViewer viewer, long id) {
        Lesson lesson = editableLesson(viewer, id);
        if (!"DRAFT".equals(lesson.status()) && !"READY".equals(lesson.status())) throw bad("Video chưa sẵn sàng để hoàn tất.");
        if (lesson.segments().isEmpty()) throw bad("Chưa có đoạn nào.");
        if (lesson.playable()) {
            for (int i = 0; i < lesson.segments().size(); i++) {
                LearnerSegment s = lesson.segments().get(i);
                if (s.start() == null || s.end() == null) throw bad("Đoạn " + (i + 1) + " chưa có thời gian bắt đầu và kết thúc.");
            }
        }
        jdbc.update("UPDATE listening_lessons SET status = 'READY', updated_at = now() WHERE id = ?", id);
        return translate(viewer, id);
    }

    /** Translates the lines that have no translation, or a stale one. Does nothing while a translation runs. */
    public VideoDetail translate(ListeningViewer viewer, long id) {
        Lesson lesson = editableLesson(viewer, id);
        if (lesson.segments().stream().noneMatch(LearnerVideoService::needsTranslation)) {
            jdbc.update("""
                    UPDATE listening_lessons SET translation_status = 'DONE', translation_message = NULL
                    WHERE id = ? AND translation_status <> 'RUNNING'
                    """, id);
            return get(viewer, id);
        }
        int started = jdbc.update("""
                UPDATE listening_lessons SET translation_status = 'RUNNING', translation_message = NULL
                WHERE id = ? AND translation_status <> 'RUNNING'
                """, id);
        // Admins are not held to the learners' daily limit.
        UUID quotaOwner = viewer.admin() ? null : viewer.id();
        if (started > 0) translateJobs.submit(() -> runTranslation(quotaOwner, id));
        return get(viewer, id);
    }

    /** @param quotaOwner whose daily limit pays for Gemini, or null for no limit */
    void runTranslation(UUID quotaOwner, long id) {
        try {
            Lesson lesson = load(id);
            List<String> all = lesson.segments().stream().map(LearnerSegment::text).toList();
            List<String> wanted = lesson.segments().stream().filter(LearnerVideoService::needsTranslation)
                    .map(LearnerSegment::text).distinct().toList();
            Map<String, String> cached = translations.cached(wanted);
            if (!cached.isEmpty()) merge(id, cached);
            List<String> missing = wanted.stream().filter(t -> !cached.containsKey(t)).toList();
            if (missing.isEmpty()) {
                finishTranslation(id, null);
                return;
            }
            if (!translations.available()) {
                finishTranslation(id, "Chưa cấu hình Gemini nên chưa dịch được.");
                return;
            }
            int allowed = quotaOwner == null ? missing.size() : maxSegmentsPerDay - todayUsage(quotaOwner)[1];
            if (allowed <= 0) {
                finishTranslation(id, "Hôm nay bạn đã dịch đủ " + maxSegmentsPerDay + " câu. Hãy dịch tiếp vào ngày mai.");
                return;
            }
            List<String> todo = missing.subList(0, Math.min(allowed, missing.size()));
            int done = 0;
            for (List<String> batch : SegmentTranslationService.batches(todo)) {
                Map<String, String> result = translations.translateBatch(lesson.title(), batch, all);
                done += result.size();
                if (quotaOwner != null) countTranslated(quotaOwner, result.size());
                merge(id, result);
            }
            int left = missing.size() - done;
            if (left <= 0) finishTranslation(id, null);
            else if (todo.size() < missing.size()) {
                finishTranslation(id, "Đã dịch " + done + " câu; còn " + left + " câu vì đã đạt giới hạn "
                        + maxSegmentsPerDay + " câu mỗi ngày.");
            } else finishTranslation(id, "Còn " + left + " câu chưa dịch được. Hãy bấm Dịch tiếp.");
        } catch (AppException ex) {
            finishTranslation(id, ex.getMessage());
        } catch (Exception ex) {
            log.warn("Translation of lesson {} failed", id, ex);
            finishTranslation(id, "Dịch chưa xong. Hãy bấm Dịch tiếp.");
        }
    }

    private void finishTranslation(long id, String problem) {
        jdbc.update("UPDATE listening_lessons SET translation_status = ?, translation_message = ? WHERE id = ?",
                problem == null ? "DONE" : "FAILED", problem == null ? null : abbreviate(problem), id);
    }

    /** Writes translations into the current lines, under a row lock so it never loses an edit saved meanwhile. */
    private void merge(long id, Map<String, String> byText) {
        tx.executeWithoutResult(status -> {
            String json = jdbc.queryForObject("SELECT segments::text FROM listening_lessons WHERE id = ? FOR UPDATE", String.class, id);
            List<LearnerSegment> segments = store.read(json);
            List<LearnerSegment> out = new ArrayList<>(segments.size());
            boolean changed = false;
            for (LearnerSegment s : segments) {
                String vi = byText.get(s.text());
                if (vi != null && needsTranslation(s)) {
                    out.add(new LearnerSegment(s.text(), s.start(), s.end(), s.pinyin(), vi, false, s.words(), s.flags(), s.timing()));
                    changed = true;
                } else {
                    out.add(s);
                }
            }
            if (changed) store.writeSegments(id, out);
        });
    }

    private List<LearnerSegment> fillFromCache(List<LearnerSegment> segments) {
        List<String> wanted = segments.stream().filter(LearnerVideoService::needsTranslation).map(LearnerSegment::text).toList();
        if (wanted.isEmpty()) return segments;
        Map<String, String> cached = translations.cached(wanted);
        if (cached.isEmpty()) return segments;
        List<LearnerSegment> out = new ArrayList<>(segments.size());
        for (LearnerSegment s : segments) {
            String vi = needsTranslation(s) ? cached.get(s.text()) : null;
            out.add(vi == null ? s : new LearnerSegment(s.text(), s.start(), s.end(), s.pinyin(), vi, false, s.words(), s.flags(), s.timing()));
        }
        return out;
    }

    static boolean needsTranslation(LearnerSegment s) {
        return s.vi() == null || s.vi().isBlank() || Boolean.TRUE.equals(s.viStale());
    }

    /** The contributor deletes a lesson that is not public; an admin deletes any. The video goes once unused. */
    public void delete(ListeningViewer viewer, long id) {
        Lesson lesson = readableLesson(viewer, id);
        if (!viewer.admin()) {
            if (!viewer.is(lesson.contributor())) throw new AppException("Bạn chỉ xoá được bài do mình đóng góp.", HttpStatus.FORBIDDEN);
            if (lesson.visibility() == Visibility.PUBLISHED) {
                throw new AppException("Bài đã công khai trong cộng đồng. Hãy báo admin nếu muốn gỡ.", HttpStatus.CONFLICT);
            }
        }
        String file = tx.execute(status -> {
            jdbc.update("DELETE FROM listening_lessons WHERE id = ?", id);
            return store.dropUnusedSource(lesson.mediaSourceId());
        });
        if (file != null) storage.delete(file);
    }

    // ── Community ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Sends a finished, translated lesson to the community. An admin's own lesson is published at once; anyone else's
     * waits for review. A file uploaded "for personal study" needs the contributor to state the right to share it.
     */
    public VideoDetail submit(ListeningViewer viewer, long id, SubmitRequest request) {
        Lesson lesson = editableLesson(viewer, id);
        if (!viewer.is(lesson.contributor()) && !viewer.admin()) throw new AppException("Không gửi được bài này.", HttpStatus.FORBIDDEN);
        if (lesson.visibility() == Visibility.PUBLISHED) throw bad("Bài đã được công khai.");
        if (!request.confirmed()) throw bad("Hãy xác nhận nội dung phù hợp để chia sẻ với cộng đồng.");
        if (!"READY".equals(lesson.status())) throw bad("Hãy bấm \"Hoàn tất\" trước khi gửi lên cộng đồng.");
        if (lesson.segmentCount() == 0 || lesson.translatedCount() < lesson.segmentCount()) {
            throw bad("Bài chưa dịch xong tiếng Việt. Hãy đợi dịch xong (hoặc bấm Dịch tiếp) rồi gửi.");
        }
        String rights;
        String note = blank(request.rightsNote()) ? null : request.rightsNote().trim();
        if ("UPLOAD".equals(lesson.provider())) {
            rights = request.usageRights() == null ? "" : request.usageRights().trim();
            if (!SHAREABLE_UPLOAD_RIGHTS.contains(rights)) {
                throw bad("Hãy cho biết bạn có quyền chia sẻ file này: tự sản xuất, Creative Commons hoặc được cho phép.");
            }
            if (!"OWN".equals(rights) && note == null) throw bad("Hãy ghi nguồn hoặc giấy phép của file.");
        } else {
            rights = lesson.videoId() != null ? "YOUTUBE_EMBED" : "OWN";
        }
        boolean publishNow = viewer.admin();
        tx.executeWithoutResult(status -> {
            store.setCategories(id, request.categoryIds(), false);
            jdbc.update("""
                    UPDATE listening_lessons SET description = ?, usage_rights = ?, rights_note = ?, visibility = ?,
                           submitted_at = now(), review_note = NULL,
                           published_at = CASE WHEN ? THEN now() ELSE NULL END,
                           reviewed_by = CASE WHEN ? THEN ? ELSE NULL END,
                           reviewed_at = CASE WHEN ? THEN now() ELSE NULL END
                    WHERE id = ?
                    """, blank(request.description()) ? null : request.description().trim(), rights, note,
                    publishNow ? "PUBLISHED" : "PENDING_REVIEW", publishNow, publishNow, viewer.id(), publishNow, id);
        });
        return get(viewer, id);
    }

    /** Takes a lesson back from review (or after a rejection) so its contributor can edit it again. */
    public VideoDetail withdraw(ListeningViewer viewer, long id) {
        Lesson lesson = readableLesson(viewer, id);
        if (!viewer.is(lesson.contributor())) throw new AppException("Bạn chỉ rút được bài do mình gửi.", HttpStatus.FORBIDDEN);
        if (lesson.visibility() != Visibility.PENDING_REVIEW && lesson.visibility() != Visibility.REJECTED) {
            throw bad(lesson.visibility() == Visibility.PUBLISHED
                    ? "Bài đã công khai. Hãy báo admin nếu muốn gỡ." : "Bài đang ở chế độ riêng tư.");
        }
        jdbc.update("UPDATE listening_lessons SET visibility = 'PRIVATE', submitted_at = NULL WHERE id = ?", id);
        return get(viewer, id);
    }

    // ── Media and practice ───────────────────────────────────────────────────────────────────────────────────────

    /** A link the browser's player can open without the login header; it expires after a few hours. */
    public MediaUrl mediaUrl(ListeningViewer viewer, long id) {
        Lesson lesson = readableLesson(viewer, id);
        if (lesson.mediaPath() == null) throw bad("Video này không có file.");
        long expires = Instant.now().getEpochSecond() + MEDIA_URL_SECONDS;
        return new MediaUrl("/api/listening/media/" + id + "?exp=" + expires + "&sig=" + sign(id, expires), expires);
    }

    public record OpenedMedia(Resource resource, String type) { }

    public OpenedMedia openMedia(long id, long expires, String signature) throws IOException {
        if (expires < Instant.now().getEpochSecond()
                || !MessageDigest.isEqual(sign(id, expires).getBytes(StandardCharsets.UTF_8),
                String.valueOf(signature).getBytes(StandardCharsets.UTF_8))) {
            throw new AppException("Link file đã hết hạn. Hãy tải lại trang.", HttpStatus.FORBIDDEN);
        }
        List<OpenedMedia> rows = jdbc.query("""
                SELECT m.media_path, m.media_type FROM listening_lessons l JOIN media_sources m ON m.id = l.media_source_id
                WHERE l.id = ? AND m.media_path IS NOT NULL
                """, (rs, i) -> {
            try {
                return new OpenedMedia(storage.load(rs.getString(1)), rs.getString(2));
            } catch (IOException ex) {
                return null;
            }
        }, id);
        if (rows.isEmpty() || rows.get(0) == null) throw notFound();
        return rows.get(0);
    }

    public ShadowingResult shadowing(ListeningViewer viewer, long id, int index, MultipartFile audio) throws IOException {
        Lesson lesson = readableLesson(viewer, id);
        if (index < 0 || index >= lesson.segments().size()) throw bad("Không có đoạn này.");
        if (audio == null || audio.isEmpty()) throw bad("Bản ghi âm rỗng.");
        if (audio.getSize() > 5 * 1024 * 1024) throw bad("Bản ghi âm quá dài.");
        SpeechScoringClient.Heard heard = scorer.assess(audio.getBytes(),
                audio.getOriginalFilename() == null ? "speech.webm" : audio.getOriginalFilename(), lesson.segments().get(index).text());
        return new ShadowingResult(Math.round(heard.accuracy() * 1000) / 1000.0, heard.transcript(), heard.accuracy() >= passAccuracy);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** The shared row of a YouTube video, created on first use with its title and channel from oEmbed. */
    long youtubeSource(String videoId) {
        List<Long> existing = jdbc.query("SELECT id FROM media_sources WHERE provider = 'YOUTUBE' AND external_id = ?",
                (rs, i) -> rs.getLong(1), videoId);
        if (!existing.isEmpty()) {
            long id = existing.get(0);
            Boolean noChannel = jdbc.queryForObject("SELECT channel_name IS NULL FROM media_sources WHERE id = ?", Boolean.class, id);
            if (Boolean.TRUE.equals(noChannel)) {
                youtubeInfo.info(videoId).ifPresent(info -> jdbc.update("""
                        UPDATE media_sources SET title = COALESCE(NULLIF(?, ''), title), channel_name = ?, channel_url = ?
                        WHERE id = ?
                        """, info.title(), info.channelName(), info.channelUrl(), id));
            }
            return id;
        }
        YoutubeTitleClient.YoutubeInfo info = youtubeInfo.info(videoId).orElse(new YoutubeTitleClient.YoutubeInfo(null, null, null));
        jdbc.update("""
                INSERT INTO media_sources (provider, external_id, title, channel_name, channel_url)
                VALUES ('YOUTUBE', ?, ?, ?, ?)
                ON CONFLICT (external_id) WHERE provider = 'YOUTUBE' DO NOTHING
                """, videoId, blank(info.title()) ? null : info.title(), info.channelName(), info.channelUrl());
        return jdbc.queryForObject("SELECT id FROM media_sources WHERE provider = 'YOUTUBE' AND external_id = ?", Long.class, videoId);
    }

    private String sourceTitle(long sourceId) {
        return jdbc.queryForObject("SELECT title FROM media_sources WHERE id = ?", String.class, sourceId);
    }

    private List<LearnerSegment> withPinyin(List<LearnerSegment> segments) {
        return withPinyin(segments, List.of());
    }

    /** Pinyin and word links for each line; a text already analysed in {@code known} is reused instead of redone. */
    private List<LearnerSegment> withPinyin(List<LearnerSegment> segments, List<LearnerSegment> known) {
        Map<String, ChinesePinyinService.Analysis> seen = reusableAnalyses(known);
        List<LearnerSegment> out = new ArrayList<>(segments.size());
        for (LearnerSegment s : segments) {
            ChinesePinyinService.Analysis a = seen.computeIfAbsent(s.text(), pinyin::analyze);
            out.add(new LearnerSegment(s.text(), s.start(), s.end(), a.pinyin(), s.vi(), s.viStale(), a.words(),
                    a.flags().isEmpty() ? null : List.copyOf(a.flags()), s.timing()));
        }
        return out;
    }

    /** The analyses already stored with lines (pinyin and words present), by text. */
    static Map<String, ChinesePinyinService.Analysis> reusableAnalyses(List<LearnerSegment> known) {
        Map<String, ChinesePinyinService.Analysis> out = new HashMap<>();
        for (LearnerSegment k : known) {
            if (k.pinyin() == null || k.words() == null) continue;
            out.putIfAbsent(k.text(), new ChinesePinyinService.Analysis(k.pinyin(), k.words(),
                    k.flags() == null ? java.util.Set.of() : new java.util.LinkedHashSet<>(k.flags())));
        }
        return out;
    }

    /** One sentence per non-empty line. */
    static List<LearnerSegment> linesToSegments(String text) {
        if (blank(text)) throw bad("Hãy nhập nội dung, mỗi dòng một câu.");
        List<LearnerSegment> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String t = line.trim().replaceAll("\\s+", " ");
            if (t.isEmpty()) continue;
            if (t.length() > 500) throw bad("Mỗi dòng tối đa 500 ký tự.");
            out.add(new LearnerSegment(t, null, null, null, null, null, null, null, null));
        }
        if (out.isEmpty()) throw bad("Hãy nhập nội dung, mỗi dòng một câu.");
        if (out.size() > 3000) throw bad("Tối đa 3000 câu.");
        return out;
    }

    /** The 11-character id from a bare id or a YouTube link, or a 400. */
    public static String youtubeId(String value) {
        String v = value == null ? "" : value.trim();
        if (VIDEO_ID.matcher(v).matches()) return v;
        try {
            URI uri = URI.create(v.contains("://") ? v : "https://" + v);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String candidate = "";
            if (host.equals("youtu.be") || host.equals("www.youtu.be")) {
                candidate = path.replaceFirst("^/", "").split("/")[0];
            } else if (host.matches("((www|m|music)\\.)?youtube(-nocookie)?\\.com")) {
                if (path.equals("/watch") && uri.getRawQuery() != null) {
                    Matcher m = Pattern.compile("(?:^|&)v=([^&]+)").matcher(uri.getRawQuery());
                    if (m.find()) candidate = m.group(1);
                } else {
                    String[] parts = path.replaceFirst("^/", "").split("/");
                    if (parts.length >= 2 && Set.of("shorts", "embed", "live", "v").contains(parts[0])) candidate = parts[1];
                }
            }
            if (VIDEO_ID.matcher(candidate).matches()) return candidate;
        } catch (IllegalArgumentException ignored) {
            // falls through to the error below
        }
        throw bad("Link hoặc mã video YouTube không hợp lệ.");
    }

    /** Magic numbers of the accepted formats, so a renamed document or program is refused. */
    static boolean looksLikeMedia(byte[] h, int n) {
        if (n < 4) return false;
        String ascii = new String(h, 0, n, StandardCharsets.ISO_8859_1);
        return ascii.startsWith("ID3")                                        // mp3 with tags
                || ((h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0)             // mp3 / aac frame
                || (n >= 8 && ascii.startsWith("ftyp", 4))                    // mp4, m4a, mov
                || (ascii.startsWith("RIFF") && n >= 12 && ascii.startsWith("WAVE", 8))
                || ascii.startsWith("OggS") || ascii.startsWith("fLaC")
                || ((h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3); // webm
    }

    static String extension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    static String mediaType(String extension) {
        return switch (extension) {
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "wav" -> "audio/wav";
            case "ogg", "oga", "opus" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            default -> "video/mp4";
        };
    }

    private long insert(UUID contributor, String title, Source source, Long mediaSourceId, String language,
                        boolean generated, String status, String rights, List<LearnerSegment> segments,
                        boolean editorial) {
        ListeningLessonStore.Stats s = ListeningLessonStore.stats(segments);
        return jdbc.queryForObject("""
                INSERT INTO listening_lessons (title, language_code, is_generated, usage_rights, segments, segment_count,
                        translated_count, est_level, duration_seconds, stats_at, contributor_id, source_type,
                        media_source_id, status, editorial)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, now(), ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, title.trim(), language, generated, rights, store.write(segments), s.count(), s.translated(),
                s.level(), s.duration(), contributor, source.name(), mediaSourceId, status, editorial);
    }

    private void checkDailyVideos(ListeningViewer viewer) {
        if (!viewer.admin() && todayUsage(viewer.id())[0] >= maxVideosPerDay) {
            throw new AppException("Hôm nay bạn đã thêm đủ " + maxVideosPerDay + " video. Hãy thêm tiếp vào ngày mai.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    /** {videos added, sentences translated} today. */
    private int[] todayUsage(UUID user) {
        if (user == null) return new int[]{0, 0};
        List<int[]> rows = jdbc.query("SELECT videos_added, segments_translated FROM learner_daily_usage WHERE user_id = ? AND day = ?",
                (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}, user, LocalDate.now());
        return rows.isEmpty() ? new int[]{0, 0} : rows.get(0);
    }

    private void countVideo(ListeningViewer viewer) {
        jdbc.update("""
                INSERT INTO learner_daily_usage (user_id, day, videos_added) VALUES (?, ?, 1)
                ON CONFLICT (user_id, day) DO UPDATE SET videos_added = learner_daily_usage.videos_added + 1
                """, viewer.id(), LocalDate.now());
    }

    private void countTranslated(UUID user, int n) {
        if (n <= 0) return;
        jdbc.update("""
                INSERT INTO learner_daily_usage (user_id, day, segments_translated) VALUES (?, ?, ?)
                ON CONFLICT (user_id, day) DO UPDATE SET segments_translated = learner_daily_usage.segments_translated + EXCLUDED.segments_translated
                """, user, LocalDate.now(), n);
    }

    private String sign(long id, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(mediaKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((id + ":" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Double round(Double seconds) {
        return seconds == null ? null : Math.round(seconds * 1000) / 1000.0;
    }

    static Double duration(List<LearnerSegment> segments) {
        return ListeningLessonStore.duration(segments);
    }

    static String estimateLevel(List<LearnerSegment> segments) {
        return ListeningLessonStore.estimateLevel(segments);
    }

    /** "Bài nghe: 你好！我叫王明…" from the first sentence, for typed text without a name. */
    static String titleFromText(List<LearnerSegment> segments) {
        String first = segments.isEmpty() ? "" : segments.get(0).text().strip();
        if (first.isEmpty()) return "Bài nghe tự nhập";
        return "Bài nghe: " + (first.codePointCount(0, first.length()) <= 24 ? first
                : first.substring(0, first.offsetByCodePoints(0, 24)) + "…");
    }

    /** The uploaded file's name without folder and extension. */
    static String titleFromFileName(String name) {
        String base = name == null ? "" : name.replaceAll("^.*[\\\\/]", "").replaceAll("\\.[^.]+$", "").replaceAll("_+", " ").strip();
        if (base.isEmpty()) return "File âm thanh";
        return base.length() <= 200 ? base : base.substring(0, 200).strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String abbreviate(String message) {
        return message.length() > 300 ? message.substring(0, 297) + "..." : message;
    }

    private static AppException bad(String message) {
        return new AppException(message, HttpStatus.BAD_REQUEST);
    }

    private static AppException notFound() {
        return new AppException("Không tìm thấy bài luyện.", HttpStatus.NOT_FOUND);
    }
}
