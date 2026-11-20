package com.yibi.backend.service;

import com.yibi.backend.dto.listening.ListeningDtos.Category;
import com.yibi.backend.dto.listening.ListeningDtos.CategoryPage;
import com.yibi.backend.dto.listening.ListeningDtos.LibraryHome;
import com.yibi.backend.dto.listening.ListeningDtos.LibrarySection;
import com.yibi.backend.dto.listening.ListeningDtos.VideoSummary;
import com.yibi.backend.exception.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.yibi.backend.service.ListeningLessonStore.PUBLIC;
import static com.yibi.backend.service.ListeningLessonStore.SUMMARY_COLUMNS;
import static com.yibi.backend.service.ListeningLessonStore.SUMMARY_FROM;

/**
 * The community library: published lessons grouped by the admins' categories, readable by guests (opening one needs
 * an account), plus a learner's shelf of saved lessons and the practice counts behind "Được luyện nhiều".
 */
@Service
public class ListeningLibraryService {

    static final int ROW = 12;
    static final int PAGE = 24;
    static final Set<String> LEVELS = Set.of("1", "2", "3", "4", "5", "6", "7-9");

    private final JdbcTemplate jdbc;
    private final ListeningLessonStore store;

    public ListeningLibraryService(JdbcTemplate jdbc, ListeningLessonStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    /** A filter on level and title, as SQL appended after {@link ListeningLessonStore#PUBLIC} and its arguments. */
    record Filter(String sql, List<Object> args) {
        static Filter of(String level, String q) {
            StringBuilder sql = new StringBuilder();
            List<Object> args = new ArrayList<>();
            if (level != null && !level.isBlank()) {
                if (!LEVELS.contains(level.trim())) throw new AppException("Cấp HSK không hợp lệ.", HttpStatus.BAD_REQUEST);
                sql.append(" AND COALESCE(l.hsk_level, l.est_level) = ?");
                args.add(level.trim());
            }
            if (q != null && !q.isBlank()) {
                sql.append(" AND (l.title ILIKE ? ESCAPE '\\' OR m.channel_name ILIKE ? ESCAPE '\\')");
                String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                args.add(like);
                args.add(like);
            }
            return new Filter(sql.toString(), args);
        }
    }

    public LibraryHome home(ListeningViewer viewer, String level, String q) {
        Filter f = Filter.of(level, q);
        List<VideoSummary> newest = rows("ORDER BY l.published_at DESC NULLS LAST, l.id DESC", viewer, f, ROW, 0);
        List<VideoSummary> popular = rows("AND l.practice_count > 0 ORDER BY l.practice_count DESC, l.published_at DESC", viewer, f, ROW, 0);

        // Up to ROW lessons per active category, newest first, in one query.
        List<Object> args = new ArrayList<>();
        args.add(viewer.idOrNobody());
        args.addAll(f.args());
        Map<Long, List<VideoSummary>> byCategory = new LinkedHashMap<>();
        Map<Long, Integer> totals = new LinkedHashMap<>();
        var summaries = store.summaries(viewer);
        jdbc.query("""
                SELECT * FROM (
                    SELECT lc.category_id AS cat, count(*) OVER (PARTITION BY lc.category_id) AS cat_total,
                           row_number() OVER (PARTITION BY lc.category_id ORDER BY l.published_at DESC NULLS LAST, l.id DESC) AS rn,
                           %s
                    %s
                    JOIN lesson_categories lc ON lc.lesson_id = l.id
                    WHERE %s%s
                ) x WHERE rn <= %d
                """.formatted(SUMMARY_COLUMNS, SUMMARY_FROM, PUBLIC, f.sql(), ROW), rs -> {
            long cat = rs.getLong("cat");
            totals.put(cat, rs.getInt("cat_total"));
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(summaries.mapRow(rs, 0));
        }, args.toArray());

        List<LibrarySection> sections = new ArrayList<>();
        for (Category c : categories(false)) {
            List<VideoSummary> videos = byCategory.get(c.id());
            if (videos == null || videos.isEmpty()) continue;
            sections.add(new LibrarySection(c, videos, totals.getOrDefault(c.id(), videos.size())));
        }
        return new LibraryHome(newest, popular, sections);
    }

    /** Every published lesson of one active category, a page at a time. */
    public CategoryPage category(ListeningViewer viewer, String slug, String level, String q, int page) {
        Category category = categories(false).stream().filter(c -> c.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new AppException("Không tìm thấy danh mục.", HttpStatus.NOT_FOUND));
        Filter f = Filter.of(level, q);
        List<Object> countArgs = new ArrayList<>();
        countArgs.add(category.id());
        countArgs.addAll(f.args());
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM listening_lessons l LEFT JOIN media_sources m ON m.id = l.media_source_id
                JOIN lesson_categories lc ON lc.lesson_id = l.id AND lc.category_id = ?
                WHERE %s%s
                """.formatted(PUBLIC, f.sql()), Long.class, countArgs.toArray());
        int safePage = Math.max(0, page);
        Filter inCategory = new Filter(" AND EXISTS (SELECT 1 FROM lesson_categories lc WHERE lc.lesson_id = l.id AND lc.category_id = ?)" + f.sql(),
                prepend(category.id(), f.args()));
        List<VideoSummary> videos = rows("ORDER BY l.published_at DESC NULLS LAST, l.id DESC", viewer, inCategory, PAGE, safePage * PAGE);
        long t = total == null ? 0 : total;
        return new CategoryPage(category, videos, safePage, (int) Math.max(1, (t + PAGE - 1) / PAGE), t);
    }

    /** Published lessons of one YouTube video, offered instead of adding the same video again. */
    public List<VideoSummary> lookup(ListeningViewer viewer, String youtubeUrl) {
        String videoId = LearnerVideoService.youtubeId(youtubeUrl);
        return rows("AND m.provider = 'YOUTUBE' AND m.external_id = ? ORDER BY l.practice_count DESC, l.published_at DESC",
                viewer, new Filter("", List.of()), 5, 0, videoId);
    }

    /** Active categories (for guests and the submit form), or all of them for admins. */
    public List<Category> categories(boolean includeInactive) {
        return jdbc.query("""
                SELECT c.id, c.name, c.slug, c.description, c.sort_order, c.is_active,
                       (SELECT count(*) FROM lesson_categories lc JOIN listening_lessons l ON l.id = lc.lesson_id
                        WHERE lc.category_id = c.id AND %s) AS lessons
                FROM video_categories c %s ORDER BY c.sort_order, c.name
                """.formatted(PUBLIC, includeInactive ? "" : "WHERE c.is_active"),
                (rs, i) -> new Category(rs.getLong("id"), rs.getString("name"), rs.getString("slug"),
                        rs.getString("description"), rs.getInt("sort_order"), rs.getBoolean("is_active"), rs.getInt("lessons")));
    }

    // ── A learner's shelf and practice ──────────────────────────────────────────────────────────────────────────

    @Transactional
    public void save(ListeningViewer viewer, long lessonId) {
        Map<String, Object> lesson = publicLesson(lessonId);
        if (viewer.is((java.util.UUID) lesson.get("contributor_id"))) {
            throw new AppException("Bài này của bạn nên đã có trong \"Video của tôi\".", HttpStatus.BAD_REQUEST);
        }
        int added = jdbc.update("INSERT INTO user_saved_lessons (user_id, lesson_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                viewer.id(), lessonId);
        if (added > 0) jdbc.update("UPDATE listening_lessons SET save_count = save_count + 1 WHERE id = ?", lessonId);
    }

    @Transactional
    public void unsave(ListeningViewer viewer, long lessonId) {
        int removed = jdbc.update("DELETE FROM user_saved_lessons WHERE user_id = ? AND lesson_id = ?", viewer.id(), lessonId);
        if (removed > 0) jdbc.update("UPDATE listening_lessons SET save_count = GREATEST(save_count - 1, 0) WHERE id = ?", lessonId);
    }

    /** Counts a learner practising someone else's published lesson, once per day. */
    @Transactional
    public void practised(ListeningViewer viewer, long lessonId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT contributor_id FROM listening_lessons l WHERE l.id = ? AND " + PUBLIC, lessonId);
        if (rows.isEmpty() || viewer.is((java.util.UUID) rows.get(0).get("contributor_id"))) return;
        int added = jdbc.update("INSERT INTO lesson_practice_days (lesson_id, user_id, day) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                lessonId, viewer.id(), LocalDate.now());
        if (added > 0) jdbc.update("UPDATE listening_lessons SET practice_count = practice_count + 1 WHERE id = ?", lessonId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> publicLesson(long lessonId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT l.contributor_id FROM listening_lessons l WHERE l.id = ? AND " + PUBLIC, lessonId);
        if (rows.isEmpty()) throw new AppException("Không tìm thấy bài luyện.", HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    /** Published lessons matching the filter, then {@code tail} (may start with AND, ends with ORDER BY). */
    private List<VideoSummary> rows(String tail, ListeningViewer viewer, Filter f, int limit, int offset, Object... tailArgs) {
        List<Object> args = new ArrayList<>();
        args.add(viewer.idOrNobody());
        args.addAll(f.args());
        args.addAll(List.of(tailArgs));
        String sql = "SELECT " + SUMMARY_COLUMNS + SUMMARY_FROM + " WHERE " + PUBLIC + f.sql() + " " + tail
                + " LIMIT " + limit + " OFFSET " + offset;
        return jdbc.query(sql, store.summaries(viewer), args.toArray());
    }

    private static List<Object> prepend(Object first, List<Object> rest) {
        List<Object> out = new ArrayList<>();
        out.add(first);
        out.addAll(rest);
        return out;
    }
}
