package com.yibi.backend.service;

import com.yibi.backend.dto.listening.ListeningDtos.AdminLessonPage;
import com.yibi.backend.dto.listening.ListeningDtos.AdminLessonRow;
import com.yibi.backend.dto.listening.ListeningDtos.ApproveRequest;
import com.yibi.backend.dto.listening.ListeningDtos.Category;
import com.yibi.backend.dto.listening.ListeningDtos.CategoryRequest;
import com.yibi.backend.dto.listening.ListeningDtos.Source;
import com.yibi.backend.dto.listening.ListeningDtos.Visibility;
import com.yibi.backend.exception.AppException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Admin side of the community library: the categories shown to learners, and the review of submitted lessons
 * (approve into 1–3 categories, reject with a note for the contributor, hide a published lesson, publish it again).
 */
@Service
public class ListeningAdminService {

    static final int PAGE = 20;

    private final JdbcTemplate jdbc;
    private final ListeningLessonStore store;
    private final ListeningLibraryService library;

    public ListeningAdminService(JdbcTemplate jdbc, ListeningLessonStore store, ListeningLibraryService library) {
        this.jdbc = jdbc;
        this.store = store;
        this.library = library;
    }

    // ── Categories ──────────────────────────────────────────────────────────────────────────────────────────────

    public List<Category> categories() {
        return library.categories(true);
    }

    public Category createCategory(CategoryRequest request) {
        String slug = slugOrFromName(request);
        Integer next = jdbc.queryForObject("SELECT COALESCE(max(sort_order), 0) + 1 FROM video_categories", Integer.class);
        try {
            jdbc.update("INSERT INTO video_categories (name, slug, description, sort_order, is_active) VALUES (?, ?, ?, ?, ?)",
                    request.name().trim(), slug, blankToNull(request.description()), next, request.active() == null || request.active());
        } catch (DuplicateKeyException ex) {
            throw new AppException("Đường dẫn \"" + slug + "\" đã được dùng cho danh mục khác.", HttpStatus.CONFLICT);
        }
        return find(slug);
    }

    public Category updateCategory(long id, CategoryRequest request) {
        String slug = slugOrFromName(request);
        try {
            int changed = jdbc.update("""
                    UPDATE video_categories SET name = ?, slug = ?, description = ?, is_active = COALESCE(?, is_active),
                           updated_at = now() WHERE id = ?
                    """, request.name().trim(), slug, blankToNull(request.description()), request.active(), id);
            if (changed == 0) throw notFound();
        } catch (DuplicateKeyException ex) {
            throw new AppException("Đường dẫn \"" + slug + "\" đã được dùng cho danh mục khác.", HttpStatus.CONFLICT);
        }
        return find(slug);
    }

    /** Lessons keep their other categories; one left without any stays published but appears only in system rows. */
    public void deleteCategory(long id) {
        if (jdbc.update("DELETE FROM video_categories WHERE id = ?", id) == 0) throw notFound();
    }

    @Transactional
    public List<Category> reorder(List<Long> ids) {
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("UPDATE video_categories SET sort_order = ?, updated_at = now() WHERE id = ?", i + 1, ids.get(i));
        }
        return categories();
    }

    private Category find(String slug) {
        return categories().stream().filter(c -> c.slug().equals(slug)).findFirst().orElseThrow(this::notFound);
    }

    private static String slugOrFromName(CategoryRequest request) {
        String slug = slugify(request.slug() == null || request.slug().isBlank() ? request.name() : request.slug());
        if (slug.isEmpty()) throw new AppException("Tên danh mục cần có chữ hoặc số.", HttpStatus.BAD_REQUEST);
        return slug;
    }

    /** "Hội thoại hằng ngày" → "hoi-thoai-hang-ngay". */
    static String slugify(String text) {
        String plain = Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        String slug = plain.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.length() <= 80 ? slug : slug.substring(0, 80).replaceAll("-+$", "");
    }

    // ── Lessons and review ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Lessons for the library tab. {@code visibility} null lists everything that is not private (private lessons are
     * the contributors' own business), except the team's own drafts; {@code editorialOnly} lists the lessons admins
     * added, drafts included. PENDING_REVIEW first by submission time.
     */
    public AdminLessonPage lessons(String visibility, Long categoryId, String q, int page, boolean editorialOnly) {
        StringBuilder where = new StringBuilder(" WHERE (l.visibility <> 'PRIVATE' OR l.editorial)");
        List<Object> args = new ArrayList<>();
        if (visibility != null && !visibility.isBlank()) {
            Visibility v;
            try {
                v = Visibility.valueOf(visibility.trim());
            } catch (IllegalArgumentException ex) {
                throw new AppException("Trạng thái không hợp lệ.", HttpStatus.BAD_REQUEST);
            }
            where.setLength(0);
            where.append(" WHERE l.visibility = ?");
            args.add(v.name());
        }
        if (editorialOnly) where.append(" AND l.editorial");
        if (categoryId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM lesson_categories lc WHERE lc.lesson_id = l.id AND lc.category_id = ?)");
            args.add(categoryId);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (l.title ILIKE ? OR u.username ILIKE ? OR m.channel_name ILIKE ?)");
            String like = "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        String from = """
                FROM listening_lessons l
                LEFT JOIN media_sources m ON m.id = l.media_source_id
                LEFT JOIN users u ON u.id = l.contributor_id
                """;
        Long total = jdbc.queryForObject("SELECT count(*) " + from + where, Long.class, args.toArray());
        int safePage = Math.max(0, page);
        List<AdminLessonRow> rows = jdbc.query("""
                SELECT l.id, l.title, l.source_type, m.external_id, (m.media_path IS NOT NULL) AS has_media, l.visibility,
                       l.status, l.translation_status, l.segment_count, l.translated_count, l.hsk_level, l.est_level,
                       l.duration_seconds, u.username, m.channel_name, l.usage_rights, l.rights_note, l.description,
                       l.review_note, l.submitted_at, l.published_at, l.practice_count, l.save_count, l.editorial
                """ + from + where + """
                 ORDER BY CASE WHEN l.visibility = 'PENDING_REVIEW' THEN 0 ELSE 1 END, l.submitted_at ASC NULLS LAST,
                          l.published_at DESC NULLS LAST, l.id DESC
                 LIMIT %d OFFSET %d
                """.formatted(PAGE, safePage * PAGE), (rs, i) -> {
            BigDecimal duration = rs.getBigDecimal("duration_seconds");
            return new AdminLessonRow(rs.getLong("id"), rs.getString("title"), Source.valueOf(rs.getString("source_type")),
                    rs.getString("external_id"), rs.getBoolean("has_media"), Visibility.valueOf(rs.getString("visibility")),
                    rs.getString("status"), rs.getString("translation_status"), rs.getInt("segment_count"),
                    rs.getInt("translated_count"), rs.getString("hsk_level"), rs.getString("est_level"),
                    duration == null ? null : duration.doubleValue(), rs.getString("username"), rs.getString("channel_name"),
                    rs.getString("usage_rights"), rs.getString("rights_note"), rs.getString("description"),
                    rs.getString("review_note"), List.of(), ListeningLessonStore.local(rs.getTimestamp("submitted_at")),
                    ListeningLessonStore.local(rs.getTimestamp("published_at")), rs.getInt("practice_count"),
                    rs.getInt("save_count"), rs.getBoolean("editorial"));
        }, args.toArray());
        var categories = store.categoriesOf(rows.stream().map(AdminLessonRow::id).toList());
        List<AdminLessonRow> withCategories = rows.stream().map(r -> new AdminLessonRow(r.id(), r.title(), r.source(),
                r.videoId(), r.hasMedia(), r.visibility(), r.status(), r.translationStatus(), r.segmentCount(),
                r.translatedCount(), r.hskLevel(), r.estimatedLevel(), r.durationSeconds(),
                r.editorial() ? ListeningLessonStore.TEAM : r.contributorName(),
                r.channelName(), r.usageRights(), r.rightsNote(), r.description(), r.reviewNote(),
                categories.getOrDefault(r.id(), List.of()), r.submittedAt(), r.publishedAt(), r.practiceCount(),
                r.saveCount(), r.editorial())).toList();
        long t = total == null ? 0 : total;
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM listening_lessons WHERE visibility = 'PENDING_REVIEW'", Integer.class);
        return new AdminLessonPage(withCategories, safePage, (int) Math.max(1, (t + PAGE - 1) / PAGE), t, pending == null ? 0 : pending);
    }

    /**
     * Publishes a submitted (or hidden, or rejected) lesson, or the team's own draft. It must be finished and fully
     * translated.
     */
    @Transactional
    public void approve(UUID adminId, long id, ApproveRequest request) {
        Map<String, Object> lesson = lesson(id);
        Visibility v = Visibility.valueOf((String) lesson.get("visibility"));
        if (v == Visibility.PRIVATE && !Boolean.TRUE.equals(lesson.get("editorial"))) {
            throw bad("Bài còn ở chế độ riêng tư; người đóng góp chưa gửi duyệt.");
        }
        if (!"READY".equals(lesson.get("status"))) {
            throw bad("Bài chưa hoàn tất. Hãy mở trình sửa, kiểm tra lời thoại rồi bấm Hoàn tất trước khi duyệt.");
        }
        int count = ((Number) lesson.get("segment_count")).intValue();
        int translated = ((Number) lesson.get("translated_count")).intValue();
        if (count == 0 || translated < count) throw bad("Bài còn " + (count - translated) + " câu chưa có bản dịch tiếng Việt.");
        String level = blankToNull(request.hskLevel());
        if (level != null && !ListeningLibraryService.LEVELS.contains(level)) throw bad("Cấp HSK không hợp lệ.");
        store.setCategories(id, request.categoryIds(), true);
        jdbc.update("""
                UPDATE listening_lessons SET visibility = 'PUBLISHED', reviewed_by = ?, reviewed_at = now(), review_note = ?,
                       published_at = COALESCE(published_at, now()), submitted_at = COALESCE(submitted_at, now()),
                       title = COALESCE(?, title), description = COALESCE(?, description), hsk_level = ?
                WHERE id = ?
                """, adminId, blankToNull(request.note()), blankToNull(request.title()), blankToNull(request.description()),
                level, id);
    }

    public void reject(UUID adminId, long id, String note) {
        Visibility v = Visibility.valueOf((String) lesson(id).get("visibility"));
        if (v != Visibility.PENDING_REVIEW) throw bad("Chỉ từ chối được bài đang chờ duyệt.");
        jdbc.update("""
                UPDATE listening_lessons SET visibility = 'REJECTED', reviewed_by = ?, reviewed_at = now(), review_note = ?
                WHERE id = ?
                """, adminId, note.trim(), id);
    }

    /** Takes a published lesson out of the library; learners who saved it no longer see it until it is published again. */
    public void hide(UUID adminId, long id, String note) {
        Visibility v = Visibility.valueOf((String) lesson(id).get("visibility"));
        if (v != Visibility.PUBLISHED) throw bad("Chỉ ẩn được bài đang công khai.");
        jdbc.update("""
                UPDATE listening_lessons SET visibility = 'HIDDEN', reviewed_by = ?, reviewed_at = now(),
                       review_note = COALESCE(?, review_note) WHERE id = ?
                """, adminId, blankToNull(note), id);
    }

    @Transactional
    public void setCategories(long id, List<Long> categoryIds) {
        lesson(id);
        store.setCategories(id, categoryIds, true);
    }

    private Map<String, Object> lesson(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT visibility, status, segment_count, translated_count, editorial FROM listening_lessons WHERE id = ?", id);
        if (rows.isEmpty()) throw new AppException("Không tìm thấy bài luyện.", HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static AppException bad(String message) {
        return new AppException(message, HttpStatus.BAD_REQUEST);
    }

    private AppException notFound() {
        return new AppException("Không tìm thấy danh mục.", HttpStatus.NOT_FOUND);
    }
}
