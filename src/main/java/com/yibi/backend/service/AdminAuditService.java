package com.yibi.backend.service;

import com.yibi.backend.dto.admin.AdminDtos.AuditEntry;
import com.yibi.backend.dto.admin.AdminDtos.AuditPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The admin audit log: one row per admin action (review, users, categories, reports). Writing it never fails the
 * action itself; a broken log write is logged and the action stands.
 */
@Slf4j
@Service
public class AdminAuditService {

    static final int PAGE = 30;

    private final JdbcTemplate jdbc;

    public AdminAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID adminId, String action, String targetType, Object targetId, String detail) {
        try {
            jdbc.update("""
                    INSERT INTO admin_audit_log (admin_id, admin_username, action, target_type, target_id, detail)
                    VALUES (?, COALESCE((SELECT username FROM users WHERE id = ?), '?'), ?, ?, ?, ?)
                    """, adminId, adminId, action, targetType, targetId == null ? null : String.valueOf(targetId),
                    detail == null ? null : detail.length() > 500 ? detail.substring(0, 500) : detail);
        } catch (RuntimeException ex) {
            log.warn("Could not write the admin audit log ({} {} {}): {}", action, targetType, targetId, ex.getMessage());
        }
    }

    public AuditPage list(String action, String q, int page) {
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        List<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            where.append(" AND action = ?");
            args.add(action.trim());
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (admin_username ILIKE ? OR target_id ILIKE ? OR detail ILIKE ?)");
            String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM admin_audit_log" + where, Long.class, args.toArray());
        int safe = Math.max(0, page);
        List<AuditEntry> entries = jdbc.query("""
                SELECT id, admin_username, action, target_type, target_id, detail, created_at FROM admin_audit_log
                """ + where + " ORDER BY created_at DESC, id DESC LIMIT " + PAGE + " OFFSET " + safe * PAGE,
                (rs, i) -> new AuditEntry(rs.getLong("id"), rs.getString("admin_username"), rs.getString("action"),
                        rs.getString("target_type"), rs.getString("target_id"), rs.getString("detail"),
                        ListeningLessonStore.local(rs.getTimestamp("created_at"))), args.toArray());
        long t = total == null ? 0 : total;
        return new AuditPage(entries, safe, (int) Math.max(1, (t + PAGE - 1) / PAGE), t);
    }
}
