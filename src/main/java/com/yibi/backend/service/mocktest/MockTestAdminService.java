package com.yibi.backend.service.mocktest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.yibi.backend.exception.AppException;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.storage.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Admin side of sample papers: an upload (PDF + recording) is read and cut by yibi-speech-service as a background
 * job, its result becomes a DRAFT paper (pictures and clips in {@link MediaStorage}), the admin checks it — answers,
 * transcripts, clip marks re-cut from the original recording — and publishes it as a new version. Learners only
 * ever see published versions; an attempt keeps the version it started on.
 */
@Slf4j
@Service
public class MockTestAdminService {

    public static final String MEDIA_PREFIX = "/api/mock-tests/media/";
    static final long MAX_PDF_BYTES = 40L * 1024 * 1024;
    static final long MAX_AUDIO_BYTES = 110L * 1024 * 1024;
    static final long MAX_RESULT_BYTES = 400L * 1024 * 1024;
    static final Set<String> AUDIO_TYPES = Set.of("mp3", "m4a", "wav", "aac", "ogg");
    static final Pattern RESULT_ENTRY = Pattern.compile("^(exam\\.json|transcript\\.json|peaks\\.json|(img|audio)/[A-Za-z0-9_.-]{1,80})$");
    static final Map<Integer, String> STAGE = Map.of(1, "Sơ cấp", 2, "Sơ cấp", 3, "Sơ cấp", 4, "Trung cấp", 5, "Trung cấp",
            6, "Trung cấp", 7, "Cao cấp");
    static final List<String> SKILL_ORDER = List.of("listening", "reading", "writing");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MediaStorage media;
    private final MockTestSpeechClient speech;
    private final MockTestCatalog catalog;

    public MockTestAdminService(JdbcTemplate jdbc, ObjectMapper json, MediaStorage media, MockTestSpeechClient speech,
                                MockTestCatalog catalog) {
        this.jdbc = jdbc;
        this.json = json;
        this.media = media;
        this.speech = speech;
        this.catalog = catalog;
    }

    public record ImportView(long id, Integer level, String status, String step, int progress, String message,
                             String pdfName, String audioName, String testId, List<String> problems,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record TestRow(String id, int level, String title, String status, String origin, Integer publishedVersion,
                          boolean hasDraft, int toReview, int attempts, OffsetDateTime updatedAt, OffsetDateTime publishedAt) {}

    public record Overview(List<TestRow> tests, List<ImportView> imports) {}

    /** What the review page edits: the draft (or a copy of the published paper), and what blocks publishing. */
    public record DraftView(String id, String status, String origin, Integer publishedVersion, boolean hasDraft,
                            JsonNode paper, String sourceAudioUrl, String peaksUrl, boolean canRecut,
                            List<String> blocking, List<String> notes) {}

    public record Clip(String key, double start, double end) {}

    // ── Overview ──────────────────────────────────────────────────────────────

    public Overview overview() {
        for (ImportView running : imports()) {
            if (isActive(running.status())) {
                try {
                    refresh(running.id());
                } catch (AppException ex) {
                    break;   // the speech service is down: the rows keep their last state
                }
            }
        }
        List<TestRow> tests = jdbc.query("""
                SELECT t.id, t.level, t.title, t.status, t.origin, t.published_version, t.draft::text AS draft, t.updated_at,
                       t.published_at, (SELECT count(*) FROM mock_test_attempts a WHERE a.test_id = t.id) AS attempts
                FROM mock_tests t ORDER BY t.level, t.id
                """, (rs, i) -> {
            String draft = rs.getString("draft");
            int toReview = draft == null ? 0 : MockTestPapers.reviewProblems(read(draft)).size();
            return new TestRow(rs.getString("id"), rs.getInt("level"), rs.getString("title"), rs.getString("status"),
                    rs.getString("origin"), (Integer) rs.getObject("published_version"), draft != null, toReview,
                    rs.getInt("attempts"), rs.getObject("updated_at", OffsetDateTime.class),
                    rs.getObject("published_at", OffsetDateTime.class));
        });
        return new Overview(tests, imports());
    }

    private static boolean isActive(String status) {
        return "QUEUED".equals(status) || "RUNNING".equals(status);
    }

    // ── Uploads ───────────────────────────────────────────────────────────────

    /** Quick checks of a PDF before uploading it with its recording. */
    public JsonNode inspect(MultipartFile pdf) {
        checkPdf(pdf);
        return speech.inspect(pdf.getResource());
    }

    public ImportView startImport(UUID adminId, MultipartFile pdf, MultipartFile audio, Integer level) {
        checkPdf(pdf);
        String ext = audioType(audio);
        if (level != null && (level < 1 || level > 9)) throw new BadRequestException("Cấp HSK không hợp lệ.");
        String pdfKey;
        String audioKey;
        try (InputStream p = pdf.getInputStream(); InputStream a = audio.getInputStream()) {
            pdfKey = media.store(p, "pdf");
            audioKey = media.store(a, ext);
        } catch (IOException ex) {
            throw new AppException("Không lưu được file tải lên.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO mock_test_imports (level, status, step, message, pdf_name, audio_name, pdf_key, audio_key, created_by)
                VALUES (?, 'QUEUED', 'upload', 'Đang gửi file sang dịch vụ xử lý', ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, level, name(pdf), name(audio), pdfKey, audioKey, adminId);
        try {
            JsonNode state = speech.submit(jobId(id), loadMedia(pdfKey), name(pdf), loadMedia(audioKey), "audio." + ext, level);
            applyJobState(id, state);
        } catch (AppException ex) {
            fail(id, ex.getMessage());
        }
        return importView(id);
    }

    private static String name(MultipartFile file) {
        String n = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replaceAll("[\\\\/\\r\\n]", "_");
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    private static void checkPdf(MultipartFile pdf) {
        if (pdf == null || pdf.isEmpty()) throw new BadRequestException("Hãy chọn file PDF của đề.");
        if (!name(pdf).toLowerCase(Locale.ROOT).endsWith(".pdf")) throw new BadRequestException("File đề phải là PDF.");
        if (pdf.getSize() > MAX_PDF_BYTES) throw new BadRequestException("File PDF quá lớn (tối đa 40 MB).");
    }

    private static String audioType(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) throw new BadRequestException("Hãy chọn file audio phần nghe.");
        String n = name(audio).toLowerCase(Locale.ROOT);
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        if (!AUDIO_TYPES.contains(ext)) throw new BadRequestException("Audio phải là MP3, M4A, WAV, AAC hoặc OGG.");
        if (audio.getSize() > MAX_AUDIO_BYTES) throw new BadRequestException("File audio quá lớn (tối đa 110 MB).");
        return ext;
    }

    static String jobId(long importId) {
        return "imp-" + importId;
    }

    /** Asks the speech service how the import is going; a finished job is saved as a draft paper. */
    public ImportView refresh(long id) {
        ImportView row = importView(id);
        if (!isActive(row.status()) || "ingest".equals(row.step())) return row;
        JsonNode state = speech.status(jobId(id));
        if (state == null) {
            fail(id, "Dịch vụ xử lý không còn giữ lượt nhập này (có thể đã khởi động lại). Hãy tải lại đề.");
        } else if ("done".equals(state.path("status").asText())) {
            int claimed = jdbc.update("""
                    UPDATE mock_test_imports SET status = 'RUNNING', step = 'ingest', progress = 97,
                           message = 'Đang lưu đề vào hệ thống', updated_at = now()
                    WHERE id = ? AND status IN ('QUEUED', 'RUNNING') AND step IS DISTINCT FROM 'ingest'
                    """, id);
            if (claimed == 1) ingest(id);
        } else {
            applyJobState(id, state);
        }
        return importView(id);
    }

    private void applyJobState(long id, JsonNode state) {
        String status = state.path("status").asText();
        if ("failed".equals(status)) {
            fail(id, state.path("error").asText("Xử lý thất bại."));
            return;
        }
        String message = state.path("message").asText("");
        if ("queued".equals(status) && state.path("position").asInt(0) > 1) {
            message = "Đang chờ, còn " + (state.path("position").asInt() - 1) + " đề phía trước";
        }
        jdbc.update("""
                UPDATE mock_test_imports SET status = ?, step = ?, progress = ?, message = ?, updated_at = now()
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING')
                """, "queued".equals(status) ? "QUEUED" : "RUNNING", state.path("step").asText(null),
                Math.max(0, Math.min(96, state.path("progress").asInt(0))), message, id);
    }

    private void fail(long id, String message) {
        jdbc.update("UPDATE mock_test_imports SET status = 'FAILED', message = ?, updated_at = now() WHERE id = ?", message, id);
    }

    private void ingest(long id) {
        Map<String, Object> row = jdbc.queryForMap("SELECT pdf_key, audio_key, created_by FROM mock_test_imports WHERE id = ?", id);
        Path dir = null;
        try {
            dir = Files.createTempDirectory("mocktest-import-");
            Path zip = speech.downloadResult(jobId(id), dir.resolve("result.zip"));
            Path folder = dir.resolve("out");
            unzip(zip, folder);
            JsonNode exam = json.readTree(folder.resolve("exam.json").toFile());
            String testId = ingestFolder(folder, (String) row.get("audio_key"), (String) row.get("pdf_key"), (UUID) row.get("created_by"));
            ArrayNode problems = json.createArrayNode();
            exam.path("problems").forEach(problems::add);
            exam.path("audio").path("problems").forEach(problems::add);
            jdbc.update("""
                    UPDATE mock_test_imports SET status = 'DONE', step = 'done', progress = 100, level = ?, test_id = ?,
                           message = 'Xong: đề đã vào danh sách nháp, hãy soát rồi xuất bản', problems = ?::jsonb, updated_at = now()
                    WHERE id = ?
                    """, exam.path("level").asInt(), testId, problems.toString(), id);
            speech.delete(jobId(id));
        } catch (Exception ex) {
            log.warn("Mock test import {} could not be saved", id, ex);
            fail(id, "Không lưu được kết quả: " + ex.getMessage());
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * Saves an import result folder (exam.json, img/, audio/, peaks.json) as a new DRAFT paper and returns its id.
     * Used for uploads and by {@code --import-mock-test} for folders made with the scripts.
     */
    @Transactional
    public String ingestFolder(Path folder, String sourceAudioKey, String sourcePdfKey, UUID adminId) throws IOException {
        JsonNode exam = json.readTree(folder.resolve("exam.json").toFile());
        int level = exam.path("level").asInt();
        String testId = uniqueTestId(level, exam.path("id").asText(""));

        Map<String, String> urls = new HashMap<>();
        for (String sub : List.of("img", "audio")) {
            Path dir = folder.resolve(sub);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    urls.put(sub + "/" + file.getFileName(), storeMedia(file, testId));
                }
            }
        }
        ObjectNode paper = toPaper(exam, testId);
        MockTestPapers.rewriteMedia(paper.path("sections"), path -> urls.getOrDefault(path, path));
        MockTestPapers.rewriteTranscripts(paper, ZhConverterUtil::toSimple);

        String peaksKey = null;
        if (Files.isRegularFile(folder.resolve("peaks.json"))) {
            peaksKey = storeMedia(folder.resolve("peaks.json"), testId).substring(MEDIA_PREFIX.length());
        }
        if (sourceAudioKey != null) {
            register(sourceAudioKey, testId, contentType(sourceAudioKey));
        }
        jdbc.update("""
                INSERT INTO mock_tests (id, level, title, status, origin, draft, source_audio_key, source_pdf_key, peaks_key, created_by)
                VALUES (?, ?, ?, 'DRAFT', 'IMPORT', ?::jsonb, ?, ?, ?, ?)
                """, testId, level, paper.path("title").asText(), paper.toString(), sourceAudioKey, sourcePdfKey, peaksKey, adminId);
        return testId;
    }

    private String uniqueTestId(int level, String examId) {
        String suffix = examId.contains("-") ? examId.substring(examId.lastIndexOf('-') + 1).replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT) : "";
        if (suffix.length() > 20) suffix = suffix.substring(0, 20);
        String base = "hsk" + level + (suffix.isEmpty() ? "" : "-" + suffix);
        String id = base;
        for (int n = 2; Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM mock_tests WHERE id = ?)", Boolean.class, id)); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    /** The app's paper from the extractor's exam.json: title, stage, time limit, counts per skill, sections. */
    ObjectNode toPaper(JsonNode exam, String testId) {
        int level = exam.path("level").asInt();
        JsonNode cover = exam.path("cover");
        ObjectNode paper = json.createObjectNode();
        paper.put("id", testId);
        paper.put("title", level >= 7 ? "HSK 7–9" : "HSK " + level);
        paper.put("level", level);
        paper.put("stage", STAGE.getOrDefault(level, ""));
        paper.put("source", "Đề mẫu HSK 3.0 chính thức (中外语言交流合作中心)");
        int total = cover.path("totalMinutes").asInt(0);
        if (total == 0) {
            for (JsonNode s : cover.path("skills")) total += s.path("minutes").asInt(0);
        }
        paper.put("durationMinutes", total);
        paper.set("sections", exam.path("sections").deepCopy());
        ArrayNode parts = paper.putArray("parts");
        for (String skill : SKILL_ORDER) {
            JsonNode minutes = cover.path("skills").path(skill).path("minutes");
            if (!minutes.isMissingNode()) parts.addObject().put("skill", skill).put("minutes", minutes.asInt());
        }
        refreshCounts(paper);
        ArrayNode notes = paper.putArray("problems");
        exam.path("problems").forEach(notes::add);
        exam.path("audio").path("problems").forEach(notes::add);
        return paper;
    }

    /** Question count per skill (keeping each skill's minutes) and in total, from the sections. */
    static void refreshCounts(ObjectNode paper) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonNode s : paper.path("sections")) counts.merge(s.path("skill").asText(), s.path("questions").size(), Integer::sum);
        Map<String, JsonNode> minutes = new HashMap<>();
        paper.path("parts").forEach(p -> minutes.put(p.path("skill").asText(), p.path("minutes")));
        ArrayNode parts = paper.putArray("parts");
        int total = 0;
        for (String skill : SKILL_ORDER) {
            if (!counts.containsKey(skill)) continue;
            ObjectNode part = parts.addObject().put("skill", skill).put("questionCount", counts.get(skill));
            JsonNode m = minutes.get(skill);
            if (m != null && m.canConvertToInt()) part.put("minutes", m.asInt());
            total += counts.get(skill);
        }
        paper.put("questionCount", total);
    }

    // ── Drafts ────────────────────────────────────────────────────────────────

    public DraftView draft(String testId) {
        Map<String, Object> row = testRow(testId);
        String draft = (String) row.get("draft");
        Integer version = (Integer) row.get("published_version");
        JsonNode paper = draft != null ? read(draft) : version != null ? catalog.paper(testId, version).deepCopy() : null;
        if (paper == null) throw new ResourceNotFoundException("Đề này chưa có nội dung.");
        String audioKey = (String) row.get("source_audio_key");
        String peaksKey = (String) row.get("peaks_key");
        List<String> notes = new ArrayList<>();
        paper.path("problems").forEach(p -> notes.add(p.asText()));
        return new DraftView(testId, (String) row.get("status"), (String) row.get("origin"), version, draft != null, paper,
                audioKey == null ? null : MEDIA_PREFIX + audioKey, peaksKey == null ? null : MEDIA_PREFIX + peaksKey,
                audioKey != null, MockTestPapers.reviewProblems(paper), notes);
    }

    @Transactional
    public DraftView saveDraft(String testId, JsonNode paper) {
        testRow(testId);
        if (!(paper instanceof ObjectNode object)) throw new BadRequestException("Nội dung đề không hợp lệ.");
        MockTestPapers.validateStructure(object);
        checkMediaPaths(object);
        object.put("id", testId);
        refreshCounts(object);
        jdbc.update("UPDATE mock_tests SET draft = ?::jsonb, title = ?, updated_at = now() WHERE id = ?",
                object.toString(), object.path("title").asText(testId), testId);
        return draft(testId);
    }

    /** Pictures and clips may only point at this site's files (no outside links slipped into a paper). */
    private static void checkMediaPaths(JsonNode paper) {
        List<String> bad = new ArrayList<>();
        MockTestPapers.rewriteMedia(paper.deepCopy(), path -> {
            if (!(path.startsWith(MEDIA_PREFIX) || path.startsWith("/mocktests/")) || path.contains("..")) bad.add(path);
            return path;
        });
        if (!bad.isEmpty()) throw new BadRequestException("Đường dẫn ảnh/audio không hợp lệ: " + bad.get(0));
    }

    /** Cuts the given clips again from the original recording and puts them in the draft. */
    public DraftView recut(String testId, List<Clip> clips) {
        Map<String, Object> row = testRow(testId);
        String audioKey = (String) row.get("source_audio_key");
        if (audioKey == null) throw new BadRequestException("Đề này không có file audio gốc nên không cắt lại được.");
        if (clips == null || clips.isEmpty() || clips.size() > 200) throw new BadRequestException("Hãy chọn đoạn cần cắt lại.");
        ObjectNode paper = (ObjectNode) draft(testId).paper();
        Map<String, ObjectNode> nodes = MockTestPapers.clips(paper);
        for (Clip clip : clips) {
            if (!nodes.containsKey(clip.key())) throw new BadRequestException("Không có đoạn audio " + clip.key());
            if (clip.start() < 0 || clip.end() <= clip.start() || clip.end() - clip.start() > 600) {
                throw new BadRequestException("Mốc của đoạn " + clip.key() + " không hợp lệ.");
            }
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("mocktest-cut-");
            Path zip = speech.cut(loadMedia(audioKey), "source." + extension(audioKey), clips, dir.resolve("clips.zip"));
            Path out = dir.resolve("clips");
            unzip(zip, out, Pattern.compile("^[A-Za-z0-9_-]{1,40}\\.mp3$"));
            for (Clip clip : clips) {
                Path file = out.resolve(clip.key() + ".mp3");
                if (!Files.isRegularFile(file)) throw new AppException("Dịch vụ không trả về đoạn " + clip.key(), HttpStatus.BAD_GATEWAY);
                ObjectNode node = nodes.get(clip.key());
                node.put("file", storeMedia(file, testId));
                node.put("start", Math.round(clip.start() * 100) / 100.0);
                node.put("end", Math.round(clip.end() * 100) / 100.0);
            }
        } catch (IOException ex) {
            throw new AppException("Không cắt lại được audio: " + ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            deleteQuietly(dir);
        }
        jdbc.update("UPDATE mock_tests SET draft = ?::jsonb, updated_at = now() WHERE id = ?", paper.toString(), testId);
        return draft(testId);
    }

    /** Publishes the draft as a new version once nothing blocks it. */
    @Transactional
    public int publish(UUID adminId, String testId) {
        Map<String, Object> row = testRow(testId);
        String draft = (String) row.get("draft");
        if (draft == null) throw new BadRequestException("Không có thay đổi nào để xuất bản.");
        JsonNode paper = read(draft);
        List<String> blocking = MockTestPapers.reviewProblems(paper);
        if (!blocking.isEmpty()) {
            throw new BadRequestException("Còn " + blocking.size() + " việc cần xử lý trước khi xuất bản: "
                    + String.join("; ", blocking.subList(0, Math.min(3, blocking.size()))));
        }
        int version = catalog.nextVersion(testId);
        jdbc.update("INSERT INTO mock_test_versions (test_id, version, paper, created_by) VALUES (?, ?, ?::jsonb, ?)",
                testId, version, MockTestPapers.forLearners(paper).toString(), adminId);
        jdbc.update("""
                UPDATE mock_tests SET status = 'PUBLISHED', published_version = ?, draft = NULL, level = ?, title = ?,
                       origin = CASE WHEN origin = 'BUNDLE' THEN 'EDITED' ELSE origin END, published_at = now(), updated_at = now()
                WHERE id = ?
                """, version, paper.path("level").asInt(), paper.path("title").asText(testId), testId);
        return version;
    }

    public void setHidden(String testId, boolean hidden) {
        Map<String, Object> row = testRow(testId);
        if (row.get("published_version") == null) throw new BadRequestException("Đề chưa xuất bản.");
        jdbc.update("UPDATE mock_tests SET status = ?, updated_at = now() WHERE id = ?", hidden ? "HIDDEN" : "PUBLISHED", testId);
    }

    /** Drops the draft; a paper never published is deleted with its files. Returns true when the paper is gone. */
    @Transactional
    public boolean discardDraft(String testId) {
        Map<String, Object> row = testRow(testId);
        if (row.get("published_version") != null) {
            jdbc.update("UPDATE mock_tests SET draft = NULL, updated_at = now() WHERE id = ?", testId);
            return false;
        }
        List<String> keys = jdbc.queryForList("SELECT storage_key FROM mock_test_media WHERE test_id = ?", String.class, testId);
        jdbc.update("DELETE FROM mock_test_media WHERE test_id = ?", testId);
        jdbc.update("DELETE FROM mock_tests WHERE id = ?", testId);
        keys.forEach(media::delete);
        if (row.get("source_pdf_key") != null) media.delete((String) row.get("source_pdf_key"));
        return true;
    }

    /** Removes a finished or failed upload from the list (a failed one also loses its files). */
    public void deleteImport(long id) {
        ImportView row = importView(id);
        if (isActive(row.status())) throw new BadRequestException("Đề đang được xử lý, hãy đợi xong.");
        Map<String, Object> keys = jdbc.queryForMap("SELECT pdf_key, audio_key FROM mock_test_imports WHERE id = ?", id);
        jdbc.update("DELETE FROM mock_test_imports WHERE id = ?", id);
        if ("FAILED".equals(row.status())) {
            keys.values().stream().filter(java.util.Objects::nonNull).forEach(k -> media.delete((String) k));
        }
        speech.delete(jobId(id));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> testRow(String testId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, origin, published_version, draft::text AS draft, source_audio_key, source_pdf_key, peaks_key
                FROM mock_tests WHERE id = ?
                """, testId);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Không tìm thấy đề " + testId);
        return rows.get(0);
    }

    private static final RowMapper<ImportView> IMPORT = (rs, i) -> new ImportView(rs.getLong("id"),
            (Integer) rs.getObject("level"), rs.getString("status"), rs.getString("step"), rs.getInt("progress"),
            rs.getString("message"), rs.getString("pdf_name"), rs.getString("audio_name"), rs.getString("test_id"),
            parseList(rs.getString("problems")), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private static List<String> parseList(String text) {
        if (text == null) return List.of();
        try {
            List<String> out = new ArrayList<>();
            new ObjectMapper().readTree(text).forEach(n -> out.add(n.asText()));
            return out;
        } catch (IOException ex) {
            return List.of();
        }
    }

    private static final String IMPORT_COLUMNS = """
            SELECT id, level, status, step, progress, message, pdf_name, audio_name, test_id, problems::text AS problems,
                   created_at, updated_at FROM mock_test_imports
            """;

    private List<ImportView> imports() {
        return jdbc.query(IMPORT_COLUMNS + " ORDER BY created_at DESC LIMIT 20", IMPORT);
    }

    public ImportView importView(long id) {
        List<ImportView> found = jdbc.query(IMPORT_COLUMNS + " WHERE id = ?", IMPORT, id);
        if (found.isEmpty()) throw new ResourceNotFoundException("Không tìm thấy lượt tải lên.");
        return found.get(0);
    }

    private org.springframework.core.io.Resource loadMedia(String key) {
        try {
            return media.load(key);
        } catch (IOException ex) {
            throw new AppException("Không đọc được file đã lưu (" + key + ").", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Stores a file of a paper and returns the URL learners load it from. */
    private String storeMedia(Path file, String testId) throws IOException {
        String ext = extension(file.getFileName().toString());
        String key;
        try (InputStream in = Files.newInputStream(file)) {
            key = media.store(in, ext);
        }
        register(key, testId, contentType(key));
        return MEDIA_PREFIX + key;
    }

    private void register(String key, String testId, String type) {
        jdbc.update("INSERT INTO mock_test_media (storage_key, test_id, content_type) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                key, testId, type);
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "bin" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String contentType(String name) {
        return switch (extension(name)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "wav" -> "audio/wav";
            case "aac" -> "audio/aac";
            case "ogg" -> "audio/ogg";
            case "json" -> "application/json";
            default -> "application/octet-stream";
        };
    }

    private void unzip(Path zip, Path target) throws IOException {
        unzip(zip, target, RESULT_ENTRY);
    }

    /** Extracts only entries with expected names (never a path outside target), within a size budget. */
    static void unzip(Path zip, Path target, Pattern allowed) throws IOException {
        Files.createDirectories(target);
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (entry.isDirectory() || !allowed.matcher(entry.getName()).matches()) continue;
                Path out = target.resolve(entry.getName()).normalize();
                if (!out.startsWith(target)) continue;
                Files.createDirectories(out.getParent());
                total += Files.copy(in, out);
                if (total > MAX_RESULT_BYTES) throw new IOException("kết quả quá lớn");
            }
        }
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp files; the OS cleans them eventually
        }
    }
}
