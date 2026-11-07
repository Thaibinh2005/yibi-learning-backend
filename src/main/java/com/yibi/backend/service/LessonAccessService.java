package com.yibi.backend.service;

import com.yibi.backend.entity.Role;
import com.yibi.backend.entity.User;
import com.yibi.backend.exception.AppException;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Learners open library lessons up to their own level; admins see everything. Level 7 is the HSK 7-9 band. */
@Service
@RequiredArgsConstructor
public class LessonAccessService {

    private static final int TOP_LEVEL = 7;

    private final SecurityUtils securityUtils;
    private final JdbcTemplate jdbc;

    public void requireLesson(Long lessonId) {
        Integer level = jdbc.query("""
                SELECT c.hsk_level FROM lessons l JOIN courses c ON c.id = l.course_id WHERE l.id = ?
                """, rs -> rs.next() ? (Integer) rs.getObject(1) : null, lessonId);
        if (level == null) throw new ResourceNotFoundException("Lesson", "id", lessonId);
        requireLevel(level);
    }

    public void requireCourse(Long courseId) {
        Integer level = jdbc.query("SELECT hsk_level FROM courses WHERE id = ?",
                rs -> rs.next() ? (Integer) rs.getObject(1) : null, courseId);
        if (level != null) requireLevel(level);
    }

    /** A level as the practice API names it: "1" to "6" or "7-9". Anything else matches no question, so it passes. */
    public void requireLevelCode(String levelCode) {
        if (levelCode == null) return;
        String code = levelCode.trim();
        if (code.equals("7-9")) requireLevel(TOP_LEVEL);
        else if (code.matches("[1-9]")) requireLevel(Math.min(Integer.parseInt(code), TOP_LEVEL));
    }

    private void requireLevel(int level) {
        User user = securityUtils.getCurrentUser();
        if (user.getRole() == Role.ROLE_ADMIN) return;
        int own = user.getHskLevel() == null ? 1 : Math.min(user.getHskLevel(), TOP_LEVEL);
        if (level > own) {
            throw new AppException("Nội dung này thuộc cấp cao hơn cấp hiện tại của bạn.", HttpStatus.FORBIDDEN);
        }
    }
}
