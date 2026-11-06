package com.yibi.backend.service;

import com.yibi.backend.entity.Course;
import com.yibi.backend.entity.LearningPath;
import com.yibi.backend.entity.PlacementAssessment;
import com.yibi.backend.entity.PlacementResult;
import com.yibi.backend.entity.SkillAttempt;
import com.yibi.backend.entity.User;
import com.yibi.backend.exception.AppException;
import com.yibi.backend.repository.CourseRepository;
import com.yibi.backend.repository.LearningPathRepository;
import com.yibi.backend.repository.SrsCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Feeds a finished placement test into the rest of the product: the learner's learning path, the
 * per-skill mastery that adaptive practice reads, review cards for missed vocabulary, and a course to start.
 * Runs inside the transaction that finishes the test.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlacementOutcomeService {

    private final JdbcTemplate jdbc;
    private final LearningPathRepository learningPathRepository;
    private final CourseRepository courseRepository;
    private final SrsCardRepository srsCardRepository;
    private final SrsService srsService;
    private final QuestionBankService questionBankService;

    public void apply(User user, PlacementAssessment attempt, PlacementResult result, List<SkillAttempt> answers) {
        UUID userId = user.getId();
        updateLearningPath(user, result.getEstimatedLevel());
        recordMastery(userId, answers);
        int cards = addReviewCards(userId, answers);
        courseRepository.findByHskLevelOrderByHskLevelAsc(result.getEstimatedLevel()).stream()
                .findFirst().map(Course::getId).ifPresent(result::setRecommendedCourseId);
        log.info("Placement outcome applied: attempt={} level={} reviewCards={} course={}",
                attempt.getId(), result.getEstimatedLevel(), cards, result.getRecommendedCourseId());
    }

    /** One path per learner: the level just measured, and a next goal one band above it unless the learner set their own. */
    private void updateLearningPath(User user, int level) {
        LearningPath path = learningPathRepository.findByUserId(user.getId())
                .orElseGet(() -> LearningPath.builder().user(user).build());
        path.setCurrentHskLevel(level);
        // A goal the learner chose stays unless they already passed it; a new path aims one band higher.
        path.setTargetHskLevel(path.getId() == null ? Math.min(7, level + 1) : Math.max(path.getTargetHskLevel(), level));
        path.setGeneratedAt(LocalDateTime.now());
        learningPathRepository.save(path);
    }

    /** Each answer counts as one practice answer, so weak skills and topics are prioritised from the first session. */
    private void recordMastery(UUID userId, List<SkillAttempt> answers) {
        Map<Long, String> levels = levelsOf(answers);
        for (SkillAttempt a : answers) {
            String level = levels.get(a.getQuestionId());
            if (level == null) continue;
            String topic = a.getTopicKey() != null && !a.getTopicKey().isBlank() ? a.getTopicKey() : "general";
            questionBankService.updateMastery(userId, level, a.getSkillKey(), topic, Boolean.TRUE.equals(a.getCorrect()));
        }
    }

    /** Words the learner got wrong go into the review deck; there is no second spaced-repetition system. */
    private int addReviewCards(UUID userId, List<SkillAttempt> answers) {
        List<Long> missed = answers.stream().filter(a -> !Boolean.TRUE.equals(a.getCorrect()))
                .map(SkillAttempt::getQuestionId).filter(Objects::nonNull).toList();
        if (missed.isEmpty()) return 0;
        String in = String.join(",", Collections.nCopies(missed.size(), "?"));
        List<Long> vocabularyIds = jdbc.query("""
                SELECT DISTINCT s.raw_record_id FROM question_bank_sources s
                WHERE s.raw_record_id IS NOT NULL AND s.question_id IN (%s)
                """.formatted(in), (rs, i) -> rs.getLong(1), missed.toArray());
        int added = 0;
        for (Long id : vocabularyIds) {
            if (srsCardRepository.existsByUserIdAndHskVocabularyRecordId(userId, id)) continue;
            try {
                srsService.addCard(userId, id);
                added++;
            } catch (AppException ex) {
                log.warn("Review card for vocabulary {} not added: {}", id, ex.getMessage());
            }
        }
        return added;
    }

    private Map<Long, String> levelsOf(List<SkillAttempt> answers) {
        Map<Long, String> levels = new HashMap<>();
        List<Long> ids = answers.stream().map(SkillAttempt::getQuestionId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return levels;
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbc.query("SELECT id, level_code FROM question_bank_items WHERE id IN (%s)".formatted(in),
                rs -> {
                    if (rs.getString("level_code") != null) levels.put(rs.getLong("id"), rs.getString("level_code"));
                }, ids.toArray());
        return levels;
    }
}
