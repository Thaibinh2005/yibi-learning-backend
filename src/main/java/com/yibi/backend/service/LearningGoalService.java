package com.yibi.backend.service;

import com.yibi.backend.dto.learningpath.LearningGoalRequest;
import com.yibi.backend.dto.learningpath.LearningGoalResponse;
import com.yibi.backend.entity.LearningPath;
import com.yibi.backend.entity.User;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.repository.LearningPathRepository;
import com.yibi.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Stores what the learner studies for and how long they study each day. */
@Service
@RequiredArgsConstructor
public class LearningGoalService {

    private static final int MAX_COURSE_LEVEL = 7;
    private static final int DEFAULT_TARGET_LEVEL = 6;
    private static final int DEFAULT_DAILY_MINUTES = 20;

    private final LearningPathRepository learningPathRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public LearningGoalResponse get(UUID userId) {
        return learningPathRepository.findByUserId(userId).map(path -> LearningGoalResponse.builder()
                        .goalType(path.getGoalType())
                        .targetLevel(Math.min(path.getTargetHskLevel(), MAX_COURSE_LEVEL))
                        .targetScore(path.getTargetScore())
                        .targetDate(path.getTargetDate())
                        .dailyMinutes(path.getDailyMinutes())
                        .goalSet(true)
                        .build())
                .orElseGet(() -> LearningGoalResponse.builder()
                        .goalType("HSK")
                        .targetLevel(DEFAULT_TARGET_LEVEL)
                        .dailyMinutes(DEFAULT_DAILY_MINUTES)
                        .goalSet(false)
                        .build());
    }

    @Transactional
    public LearningGoalResponse save(UUID userId, LearningGoalRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        return save(user, request);
    }

    @Transactional
    public LearningGoalResponse save(User user, LearningGoalRequest request) {
        if (request.getTargetDate() != null && request.getTargetDate().isBefore(LocalDate.now())) {
            throw new BadRequestException("Ngày mục tiêu phải từ hôm nay trở đi.");
        }
        LearningPath path = learningPathRepository.findByUserId(user.getId())
                .orElseGet(() -> LearningPath.builder().user(user).build());
        int current = currentLevel(user);
        path.setCurrentHskLevel(current);
        // The target can never sit below the level already reached (chk_path_hsk).
        path.setTargetHskLevel(Math.max(request.getTargetLevel(), current));
        path.setGoalType(request.getGoalType());
        path.setTargetScore(request.getTargetScore());
        path.setTargetDate(request.getTargetDate());
        path.setDailyMinutes(request.getDailyMinutes());
        path.setGeneratedAt(LocalDateTime.now());
        learningPathRepository.save(path);
        return get(user.getId());
    }

    private static int currentLevel(User user) {
        Integer measured = user.getHskLevel();
        int level = measured != null ? measured
                : user.getSelfReportedLevel() != null ? user.getSelfReportedLevel() : 1;
        return Math.max(1, Math.min(level, MAX_COURSE_LEVEL));
    }
}
