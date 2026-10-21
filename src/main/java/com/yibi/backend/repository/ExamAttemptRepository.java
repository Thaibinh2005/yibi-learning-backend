package com.yibi.backend.repository;

import com.yibi.backend.entity.ExamAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ExamAttemptRepository extends JpaRepository<ExamAttempt, Long> {

    List<ExamAttempt> findByUserIdOrderByStartedAtDesc(UUID userId);

    List<ExamAttempt> findByExamId(Long examId);

    /** Admin stats: pass rate for an exam */
    @Query("SELECT COUNT(a) FROM ExamAttempt a WHERE a.exam.id = :examId AND a.passed = true")
    long countPassedByExamId(@Param("examId") Long examId);

    /** Admin stats: total attempts for an exam */
    long countByExamId(Long examId);
}
