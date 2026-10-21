package com.yibi.backend.repository;

import com.yibi.backend.entity.UserProgress;
import com.yibi.backend.entity.ProgressStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserProgressRepository extends JpaRepository<UserProgress, Long> {

    Optional<UserProgress> findByUserIdAndLessonId(UUID userId, Long lessonId);

    List<UserProgress> findByUserId(UUID userId);

    List<UserProgress> findByUserIdAndStatus(UUID userId, ProgressStatus status);

    @Query("SELECT COUNT(p) FROM UserProgress p WHERE p.user.id = :userId AND p.status = 'COMPLETED'")
    long countCompletedLessons(@Param("userId") UUID userId);
}
