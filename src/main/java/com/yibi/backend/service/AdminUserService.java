package com.yibi.backend.service;

import com.yibi.backend.dto.admin.AdminDtos.AdminUserPage;
import com.yibi.backend.dto.admin.AdminDtos.AdminUserRow;
import com.yibi.backend.exception.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * User management for admins: find users, lock or unlock an account (a lock also signs it out everywhere), and give
 * or take the admin role. An admin cannot lock or demote themselves, and the last admin cannot be demoted.
 */
@Service
public class AdminUserService {

    static final int PAGE = 25;

    private final JdbcTemplate jdbc;
    private final TokenBlacklistService tokens;
    private final AdminAuditService audit;

    public AdminUserService(JdbcTemplate jdbc, TokenBlacklistService tokens, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.audit = audit;
    }

    public AdminUserPage list(String q, String role, Boolean active, int page) {
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.append(" AND (u.username ILIKE ? OR u.email ILIKE ?)");
            String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        if (role != null && !role.isBlank()) {
            where.append(" AND u.role = ?");
            args.add(validRole(role));
        }
        if (active != null) {
            where.append(" AND u.is_active = ?");
            args.add(active);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM users u" + where, Long.class, args.toArray());
        int safe = Math.max(0, page);
        List<AdminUserRow> rows = jdbc.query("""
                SELECT u.id, u.username, u.email, u.role, u.is_active, u.hsk_level, (u.google_sub IS NOT NULL) AS google,
                       u.created_at, u.last_login,
                       (SELECT count(*) FROM listening_lessons l WHERE l.contributor_id = u.id AND NOT l.editorial) AS contributed,
                       (SELECT count(*) FROM listening_lessons l WHERE l.contributor_id = u.id AND NOT l.editorial
                          AND l.visibility = 'PUBLISHED') AS published,
                       (SELECT count(*) FROM content_reports r WHERE r.reporter_id = u.id AND r.status = 'OPEN') AS reports
                FROM users u
                """ + where + " ORDER BY u.created_at DESC LIMIT " + PAGE + " OFFSET " + safe * PAGE,
                (rs, i) -> new AdminUserRow(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("email"),
                        rs.getString("role"), rs.getBoolean("is_active"), (Integer) rs.getObject("hsk_level"),
                        rs.getBoolean("google"), rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("last_login") == null ? null : rs.getTimestamp("last_login").toLocalDateTime(),
                        rs.getInt("contributed"), rs.getInt("published"), rs.getInt("reports")), args.toArray());
        long t = total == null ? 0 : total;
        return new AdminUserPage(rows, safe, (int) Math.max(1, (t + PAGE - 1) / PAGE), t);
    }

    /** Locks (signing the user out everywhere) or unlocks an account. */
    @Transactional
    public void setActive(UUID adminId, UUID userId, boolean active, String reason) {
        if (adminId.equals(userId)) throw bad("Bạn không thể khoá tài khoản của chính mình.");
        Map<String, Object> user = user(userId);
        if (!active && "ROLE_ADMIN".equals(user.get("role"))) {
            throw bad("Hãy bỏ quyền admin của tài khoản này trước khi khoá.");
        }
        jdbc.update("UPDATE users SET is_active = ?, updated_at = now() WHERE id = ?", active, userId);
        if (!active) tokens.revokeAllRefreshTokens((String) user.get("username"));
        audit.record(adminId, active ? "USER_UNLOCK" : "USER_LOCK", "USER", user.get("username"),
                reason == null || reason.isBlank() ? null : reason.trim());
    }

    @Transactional
    public void setRole(UUID adminId, UUID userId, String role) {
        String wanted = validRole(role);
        Map<String, Object> user = user(userId);
        if (wanted.equals(user.get("role"))) return;
        if (adminId.equals(userId)) throw bad("Bạn không thể tự đổi quyền của chính mình.");
        if ("ROLE_USER".equals(wanted)) {
            Integer admins = jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ROLE_ADMIN' AND is_active", Integer.class);
            if (admins != null && admins <= 1) throw bad("Phải còn ít nhất một admin.");
        }
        if ("ROLE_ADMIN".equals(wanted) && !Boolean.TRUE.equals(user.get("is_active"))) {
            throw bad("Hãy mở khoá tài khoản trước khi cấp quyền admin.");
        }
        jdbc.update("UPDATE users SET role = ?, updated_at = now() WHERE id = ?", wanted, userId);
        // Tokens carry the old role: make the user sign in again.
        tokens.revokeAllRefreshTokens((String) user.get("username"));
        audit.record(adminId, "USER_ROLE", "USER", user.get("username"), user.get("role") + " → " + wanted);
    }

    private Map<String, Object> user(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT username, role, is_active FROM users WHERE id = ?", userId);
        if (rows.isEmpty()) throw new AppException("Không tìm thấy người dùng.", HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    static String validRole(String role) {
        String r = role == null ? "" : role.trim().toUpperCase();
        if (!r.startsWith("ROLE_")) r = "ROLE_" + r;
        if (!r.equals("ROLE_USER") && !r.equals("ROLE_ADMIN")) throw bad("Quyền không hợp lệ.");
        return r;
    }

    private static AppException bad(String message) {
        return new AppException(message, HttpStatus.BAD_REQUEST);
    }
}
