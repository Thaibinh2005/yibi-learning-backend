package com.yibi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.dto.admin.AdminDtos.CreateReportRequest;
import com.yibi.backend.dto.admin.AdminDtos.ReportPage;
import com.yibi.backend.dto.admin.AdminDtos.ReportRow;
import com.yibi.backend.exception.AppException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Learners report mistakes in content: a listening line, a whole listening lesson, or a word. The target must exist
 * and be visible to the reporter. Admins see each report with what it points at and a link to fix it, then close it
 * as resolved or dismissed.
 */
@Service
public class ContentReportService {

    static final int PAGE = 20;
    static final Set<String> TYPES = Set.of("LISTENING_SEGMENT", "LISTENING_LESSON", "VOCABULARY");
    static final Set<String> REASONS = Set.of("WRONG_TEXT", "WRONG_TIME", "WRONG_TRANSLATION", "WRONG_PINYIN",
            "WRONG_MEANING", "BAD_EXAMPLE", "INAPPROPRIATE", "OTHER");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AdminAuditService audit;

    public ContentReportService(JdbcTemplate jdbc, ObjectMapper mapper, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
    }

    public void create(ListeningViewer viewer, CreateReportRequest request) {
        String type = request.targetType().trim();
        String reason = request.reason().trim();
        if (!TYPES.contains(type)) throw bad("Loại nội dung không hợp lệ.");
        if (!REASONS.contains(reason)) throw bad("Lý do không hợp lệ.");
        if ("OTHER".equals(reason) && (request.note() == null || request.note().isBlank())) {
            throw bad("Hãy mô tả ngắn lỗi bạn thấy.");
        }
        Integer ref = "LISTENING_SEGMENT".equals(type) ? request.targetRef() : null;
        checkTarget(viewer, type, request.targetId(), ref);
        try {
            jdbc.update("""
                    INSERT INTO content_reports (reporter_id, target_type, target_id, target_ref, reason, note)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, viewer.id(), type, request.targetId(), ref, reason,
                    request.note() == null || request.note().isBlank() ? null : request.note().trim());
        } catch (DuplicateKeyException ex) {
            throw new AppException("Bạn đã báo lỗi này rồi, admin sẽ xem sớm.", HttpStatus.CONFLICT);
        }
    }

    /** The target exists and the reporter can see it (a listening lesson: their own or a published one). */
    private void checkTarget(ListeningViewer viewer, String type, long id, Integer ref) {
        if ("VOCABULARY".equals(type)) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM hsk_vocabulary_records WHERE raw_record_id = ?", Integer.class, id);
            if (n == null || n == 0) throw new AppException("Không tìm thấy từ này.", HttpStatus.NOT_FOUND);
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT contributor_id, visibility, status, segment_count FROM listening_lessons WHERE id = ?
                """, id);
        if (rows.isEmpty()) throw new AppException("Không tìm thấy bài luyện.", HttpStatus.NOT_FOUND);
        Map<String, Object> l = rows.get(0);
        boolean visible = viewer.admin() || viewer.is((UUID) l.get("contributor_id"))
                || ("PUBLISHED".equals(l.get("visibility")) && "READY".equals(l.get("status")));
        if (!visible) throw new AppException("Không tìm thấy bài luyện.", HttpStatus.NOT_FOUND);
        if ("LISTENING_SEGMENT".equals(type)) {
            int count = ((Number) l.get("segment_count")).intValue();
            if (ref == null || ref < 0 || ref >= count) throw bad("Câu được báo không tồn tại.");
        }
    }

    public ReportPage list(String status, String type, int page) {
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND r.status = ?");
            args.add(status.trim());
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND r.target_type = ?");
            args.add(type.trim());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM content_reports r" + where, Long.class, args.toArray());
        int safe = Math.max(0, page);
        List<ReportRow> rows = jdbc.query("""
                SELECT r.id, r.target_type, r.target_id, r.target_ref, r.reason, r.note, r.status, r.created_at,
                       r.resolution_note, r.resolved_at, rep.username AS reporter, adm.username AS resolver,
                       l.title AS lesson_title,
                       CASE WHEN r.target_type = 'LISTENING_SEGMENT' THEN l.segments -> r.target_ref END AS segment,
                       v.simplified AS word, v.level_code,
                       (SELECT count(*) FROM content_reports o WHERE o.status = 'OPEN' AND o.target_type = r.target_type
                          AND o.target_id = r.target_id AND o.target_ref IS NOT DISTINCT FROM r.target_ref) AS same_open
                FROM content_reports r
                LEFT JOIN users rep ON rep.id = r.reporter_id
                LEFT JOIN users adm ON adm.id = r.resolved_by
                LEFT JOIN listening_lessons l ON r.target_type <> 'VOCABULARY' AND l.id = r.target_id
                LEFT JOIN hsk_vocabulary_records v ON r.target_type = 'VOCABULARY' AND v.raw_record_id = r.target_id
                """ + where + """
                 ORDER BY CASE WHEN r.status = 'OPEN' THEN 0 ELSE 1 END, r.created_at DESC
                """ + " LIMIT " + PAGE + " OFFSET " + safe * PAGE, (rs, i) -> {
            String t = rs.getString("target_type");
            long targetId = rs.getLong("target_id");
            Integer ref = (Integer) rs.getObject("target_ref");
            String title;
            String text = null;
            String link;
            if ("VOCABULARY".equals(t)) {
                String word = rs.getString("word");
                title = word == null ? "(từ đã bị xoá)" : word.replaceAll("[0-9]+$", "") + " · HSK " + rs.getString("level_code");
                link = null;
            } else {
                title = rs.getString("lesson_title") == null ? "(bài đã bị xoá)" : rs.getString("lesson_title");
                link = "/listening/" + targetId + "/edit";
                text = segmentText(rs.getString("segment"));
            }
            return new ReportRow(rs.getLong("id"), t, targetId, ref, rs.getString("reason"), rs.getString("note"),
                    rs.getString("status"), rs.getString("reporter"), ListeningLessonStore.local(rs.getTimestamp("created_at")),
                    title, text, link, rs.getString("resolution_note"), rs.getString("resolver"),
                    ListeningLessonStore.local(rs.getTimestamp("resolved_at")), rs.getInt("same_open"));
        }, args.toArray());
        Integer open = jdbc.queryForObject("SELECT count(*) FROM content_reports WHERE status = 'OPEN'", Integer.class);
        long tt = total == null ? 0 : total;
        return new ReportPage(rows, safe, (int) Math.max(1, (tt + PAGE - 1) / PAGE), tt, open == null ? 0 : open);
    }

    /** "你好 — Xin chào" from a stored listening line. */
    private String segmentText(String json) {
        if (json == null) return null;
        try {
            JsonNode s = mapper.readTree(json);
            String zh = s.path("text").asText("");
            String vi = s.path("vi").asText("");
            return vi.isBlank() ? zh : zh + " — " + vi;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Closes a report; the other open reports of the same spot and reason close with it. */
    public void resolve(UUID adminId, long id, String status, String note) {
        String s = status == null ? "" : status.trim();
        if (!s.equals("RESOLVED") && !s.equals("DISMISSED")) throw bad("Trạng thái không hợp lệ.");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT target_type, target_id, target_ref, reason, status FROM content_reports WHERE id = ?", id);
        if (rows.isEmpty()) throw new AppException("Không tìm thấy báo lỗi.", HttpStatus.NOT_FOUND);
        Map<String, Object> r = rows.get(0);
        if (!"OPEN".equals(r.get("status"))) throw bad("Báo lỗi này đã được xử lý.");
        int closed = jdbc.update("""
                UPDATE content_reports SET status = ?, resolved_by = ?, resolved_at = now(), resolution_note = ?
                WHERE status = 'OPEN' AND target_type = ? AND target_id = ? AND target_ref IS NOT DISTINCT FROM ? AND reason = ?
                """, s, adminId, note == null || note.isBlank() ? null : note.trim(), r.get("target_type"), r.get("target_id"),
                r.get("target_ref"), r.get("reason"));
        audit.record(adminId, "RESOLVED".equals(s) ? "REPORT_RESOLVE" : "REPORT_DISMISS", (String) r.get("target_type"),
                r.get("target_id") + (r.get("target_ref") == null ? "" : "#" + r.get("target_ref")),
                r.get("reason") + (closed > 1 ? " (" + closed + " báo cáo)" : ""));
    }

    private static AppException bad(String message) {
        return new AppException(message, HttpStatus.BAD_REQUEST);
    }
}
