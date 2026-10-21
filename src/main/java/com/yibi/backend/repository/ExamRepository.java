package com.yibi.backend.repository;

import com.yibi.backend.entity.Exam;
import com.yibi.backend.entity.ExamType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamRepository extends JpaRepository<Exam, Long> {
    List<Exam> findByExamType(ExamType examType);
    List<Exam> findByHskLevel(Integer hskLevel);
    List<Exam> findByHskLevelAndExamType(Integer hskLevel, ExamType examType);
    Optional<Exam> findFirstByExamTypeOrderByCreatedAtDesc(ExamType examType);
}
