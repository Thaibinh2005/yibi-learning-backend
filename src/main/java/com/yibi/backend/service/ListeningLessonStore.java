package com.yibi.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.dto.listening.ListeningDtos.CategoryRef;
import com.yibi.backend.dto.listening.ListeningDtos.LearnerSegment;
import com.yibi.backend.dto.listening.ListeningDtos.Source;
import com.yibi.backend.dto.listening.ListeningDtos.VideoSummary;
import com.yibi.backend.dto.listening.ListeningDtos.Visibility;
import com.yibi.backend.exception.AppException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Reading and writing listening_lessons rows, shared by the contributor, library and admin services: the card query,
 * the segments JSON, and the counts kept beside it (translated lines, estimated level, length) so lists never parse it.
 */
@Slf4j
@Component
public class ListeningLessonStore {

    private static final TypeReference<List<LearnerSegment>> SEGMENTS = new TypeReference<>() { };

    /** Card columns; the query that uses them must bind the viewer id first (for {@code saved}). */
    static final String SUMMARY_COLUMNS = """
            l.id, l.title, l.source_type, m.external_id AS video_id, (m.media_path IS NOT NULL) AS has_media, l.status,
            l.status_message, l.translation_status, l.segment_count, l.translated_count, l.updated_at,
            l.duration_seconds, COALESCE(l.hsk_level, l.est_level) AS level, l.visibility, l.contributor_id,
            m.channel_name, u.username AS contributor_name, l.practice_count, (sv.user_id IS NOT NULL) AS saved
            """;

    static final String SUMMARY_FROM = """
            FROM listening_lessons l
            LEFT JOIN media_sources m ON m.id = l.media_source_id
            LEFT JOIN users u ON u.id = l.contributor_id
            LEFT JOIN user_saved_lessons sv ON sv.lesson_id = l.id AND sv.user_id = ?
            """;

    /** What everyone may see: approved and finished. */
    static final String PUBLIC = "l.visibility = 'PUBLISHED' AND l.status = 'READY'";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ListeningLessonStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    RowMapper<VideoSummary> summaries(ListeningViewer viewer) {
        return (rs, i) -> {
            BigDecimal duration = rs.getBigDecimal("duration_seconds");
            UUID contributor = rs.getObject("contributor_id", UUID.class);
            return new VideoSummary(rs.getLong("id"), rs.getString("title"), Source.valueOf(rs.getString("source_type")),
                    rs.getString("video_id"), rs.getBoolean("has_media"), rs.getString("status"),
                    rs.getString("status_message"), rs.getString("translation_status"), rs.getInt("segment_count"),
                    rs.getInt("translated_count"), VocabDeckService.fromUtc(rs.getTimestamp("updated_at")),
                    duration == null ? null : duration.doubleValue(), rs.getString("level"),
                    Visibility.valueOf(rs.getString("visibility")), viewer.is(contributor), rs.getBoolean("saved"),
                    rs.getString("channel_name"), rs.getString("contributor_name"), rs.getInt("practice_count"));
        };
    }

    // ── Segments and their counts ───────────────────────────────────────────────────────────────────────────────

    record Stats(int count, int translated, String level, Double duration) { }

    static Stats stats(List<LearnerSegment> segments) {
        int translated = (int) segments.stream()
                .filter(s -> s.vi() != null && !s.vi().isBlank() && !Boolean.TRUE.equals(s.viStale())).count();
        return new Stats(segments.size(), translated, estimateLevel(segments), duration(segments));
    }

    /** Replaces a lesson's lines and the counts derived from them; {@code extraSql} adds more "col = ?" pairs. */
    void writeSegments(long id, List<LearnerSegment> segments) {
        Stats s = stats(segments);
        jdbc.update("""
                UPDATE listening_lessons SET segments = ?::jsonb, segment_count = ?, translated_count = ?, est_level = ?,
                       duration_seconds = ?, stats_at = now()
                WHERE id = ?
                """, write(segments), s.count(), s.translated(), s.level(), s.duration(), id);
    }

    /** Lessons saved before the counts existed (or by the old admin tool) get them once, at start-up. */
    @PostConstruct
    void backfillStats() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, segments::text AS segments FROM listening_lessons WHERE stats_at IS NULL LIMIT 5000");
        for (Map<String, Object> row : rows) {
            try {
                Stats s = stats(read((String) row.get("segments")));
                jdbc.update("""
                        UPDATE listening_lessons SET translated_count = ?, est_level = ?, duration_seconds = ?,
                               segment_count = ?, stats_at = now() WHERE id = ?
                        """, s.translated(), s.level(), s.duration(), s.count(), row.get("id"));
            } catch (RuntimeException ex) {
                log.warn("Could not compute counts of listening lesson {}: {}", row.get("id"), ex.getMessage());
            }
        }
        if (!rows.isEmpty()) log.info("Computed counts of {} listening lessons", rows.size());
    }

    List<LearnerSegment> read(String json) {
        try {
            return json == null ? List.of() : mapper.readValue(json, SEGMENTS);
        } catch (JsonProcessingException ex) {
            throw new AppException("Dữ liệu bài luyện bị hỏng.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    String write(List<LearnerSegment> segments) {
        try {
            return mapper.writeValueAsString(segments);
        } catch (JsonProcessingException ex) {
            throw new AppException("Không ghi được dữ liệu bài luyện.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── Categories ──────────────────────────────────────────────────────────────────────────────────────────────

    List<CategoryRef> categoriesOf(long lessonId) {
        return categoriesOf(List.of(lessonId)).getOrDefault(lessonId, List.of());
    }

    Map<Long, List<CategoryRef>> categoriesOf(Collection<Long> lessonIds) {
        Map<Long, List<CategoryRef>> out = new HashMap<>();
        if (lessonIds.isEmpty()) return out;
        String marks = String.join(",", java.util.Collections.nCopies(lessonIds.size(), "?"));
        jdbc.query("""
                SELECT lc.lesson_id, c.id, c.name, c.slug FROM lesson_categories lc
                JOIN video_categories c ON c.id = lc.category_id
                WHERE lc.lesson_id IN (%s) ORDER BY c.sort_order, c.name
                """.formatted(marks), rs -> {
            out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                    .add(new CategoryRef(rs.getLong(2), rs.getString(3), rs.getString(4)));
        }, lessonIds.toArray());
        return out;
    }

    /** Checks 1–3 distinct existing categories (active ones only unless {@code allowInactive}) and stores them. */
    void setCategories(long lessonId, List<Long> categoryIds, boolean allowInactive) {
        List<Long> wanted = categoryIds == null ? List.of() : categoryIds.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty() || wanted.size() > 3) {
            throw new AppException("Hãy chọn từ 1 đến 3 danh mục.", HttpStatus.BAD_REQUEST);
        }
        String marks = String.join(",", java.util.Collections.nCopies(wanted.size(), "?"));
        Integer found = jdbc.queryForObject("SELECT count(*) FROM video_categories WHERE id IN (%s)%s"
                .formatted(marks, allowInactive ? "" : " AND is_active"), Integer.class, wanted.toArray());
        if (found == null || found != wanted.size()) {
            throw new AppException("Có danh mục không tồn tại hoặc đã bị ẩn.", HttpStatus.BAD_REQUEST);
        }
        jdbc.update("DELETE FROM lesson_categories WHERE lesson_id = ?", lessonId);
        for (Long c : wanted) jdbc.update("INSERT INTO lesson_categories (lesson_id, category_id) VALUES (?, ?)", lessonId, c);
    }

    // ── Media sources ───────────────────────────────────────────────────────────────────────────────────────────

    /** Removes a source no lesson plays any more; returns the stored file to delete, if it was an upload. */
    String dropUnusedSource(Long sourceId) {
        if (sourceId == null) return null;
        List<String> removed = jdbc.query("""
                DELETE FROM media_sources m WHERE m.id = ?
                  AND NOT EXISTS (SELECT 1 FROM listening_lessons l WHERE l.media_source_id = m.id)
                RETURNING media_path
                """, (rs, i) -> rs.getString(1), sourceId);
        return removed.isEmpty() ? null : removed.get(0);
    }

    // ── Derived values ──────────────────────────────────────────────────────────────────────────────────────────

    /** When the last sentence ends, or null when no sentence is timed yet. */
    static Double duration(List<LearnerSegment> segments) {
        return segments.stream().map(LearnerSegment::end).filter(Objects::nonNull).max(Double::compare).orElse(null);
    }

    /**
     * A rough HSK level: the level that covers 85% of the HSK words used (each word counted once).
     * "1".."6", "7-9", or null with fewer than five such words.
     */
    static String estimateLevel(List<LearnerSegment> segments) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (LearnerSegment s : segments) {
            if (s.words() == null) continue;
            for (ChinesePinyinService.Token t : s.words()) {
                if (t.hsk() == null || t.hsk().isBlank()) continue;
                int level = t.hsk().startsWith("7") ? 7 : Character.isDigit(t.hsk().charAt(0)) ? t.hsk().charAt(0) - '0' : 0;
                if (level >= 1) levels.putIfAbsent(t.w(), level);
            }
        }
        if (levels.size() < 5) return null;
        List<Integer> sorted = levels.values().stream().sorted().toList();
        int level = sorted.get((int) Math.ceil(sorted.size() * 0.85) - 1);
        return level >= 7 ? "7-9" : String.valueOf(level);
    }
}
