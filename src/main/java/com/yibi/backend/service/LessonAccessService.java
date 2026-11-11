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

/**
 * The textbook library is open at every level, whatever the learner's place on the path: a library lesson only has
 * to exist. Adaptive practice stays limited to the learner's own level; admins see everything. Level 7 is HSK 7-9.
 */
@Service
@RequiredArgsConstructor
public class LessonAccessService {

    private static final int TOP_LEVEL = 7;

    private final SecurityUtils securityUtils;
    private final JdbcTemplate jdbc;

    public void requireLesson(Long lessonId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM lessons WHERE id = ?)", Boolean.class, lessonId);
        if (!Boolean.TRUE.equals(exists)) throw new ResourceNotFoundException("Lesson", "id", lessonId);
    }

    /** A course's lesson list is open to everyone, guests included; an unknown course simply has no lessons. */
    public void requireCourse(Long courseId) {
        // Nothing to check: kept so the library endpoints have one place to add a rule later.
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
