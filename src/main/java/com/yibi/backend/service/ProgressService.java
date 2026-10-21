package com.yibi.backend.service;

import com.yibi.backend.dto.progress.OverallProgressResponse;
import com.yibi.backend.dto.progress.ProgressUpdateDto;
import com.yibi.backend.dto.progress.UserProgressResponse;
import com.yibi.backend.entity.Lesson;
import com.yibi.backend.entity.ProgressStatus;
import com.yibi.backend.entity.User;
import com.yibi.backend.entity.UserProgress;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressService {

    private final UserProgressRepository userProgressRepository;
    private final LessonRepository lessonRepository;
    private final UserRepository userRepository;
    private final SrsCardRepository srsCardRepository;
    private final ExamAttemptRepository examAttemptRepository;

    @Transactional
    public UserProgressResponse updateProgress(UUID userId, ProgressUpdateDto dto) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        Lesson lesson = lessonRepository.findById(dto.getLessonId())
                .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", dto.getLessonId()));

        UserProgress progress = userProgressRepository.findByUserIdAndLessonId(userId, dto.getLessonId())
                .orElse(UserProgress.builder()
                        .user(user)
                        .lesson(lesson)
                        .status(ProgressStatus.NOT_STARTED)
                        .build());

        progress.setStatus(dto.getStatus());
        if (dto.getScore() != null) {
            progress.setScore(dto.getScore());
        }
        if (dto.getStatus() == ProgressStatus.COMPLETED) {
            progress.setCompletedAt(LocalDateTime.now());
        }

        UserProgress saved = userProgressRepository.save(progress);
        log.info("User progress updated: userId={}, lessonId={}, status={}", userId, lesson.getId(), dto.getStatus());

        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<UserProgressResponse> getUserProgress(UUID userId) {
        return userProgressRepository.findByUserId(userId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OverallProgressResponse getOverallProgress(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        long completedCount = userProgressRepository.countCompletedLessons(userId);
        long srsDue = srsCardRepository.countDueCards(userId, LocalDate.now());
        long srsTotal = srsCardRepository.countByUserId(userId);
        long examCount = examAttemptRepository.findByUserIdOrderByStartedAtDesc(userId).size();

        return OverallProgressResponse.builder()
                .completedLessons(completedCount)
                .currentHskLevel(user.getHskLevel() != null ? user.getHskLevel() : 1)
                .srsCardsDueToday(srsDue)
                .srsTotalCards(srsTotal)
                .examsAttempted(examCount)
                .build();
    }

    private UserProgressResponse mapToResponse(UserProgress p) {
        Lesson l = p.getLesson();
        return UserProgressResponse.builder()
                .lessonId(l != null ? l.getId() : null)
                .lessonTitle(l != null ? l.getTitle() : null)
                .courseId(l != null && l.getCourse() != null ? l.getCourse().getId() : null)
                .courseTitle(l != null && l.getCourse() != null ? l.getCourse().getTitle() : null)
                .status(p.getStatus())
                .score(p.getScore())
                .completedAt(p.getCompletedAt())
                .build();
    }
}
