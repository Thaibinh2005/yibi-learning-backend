package com.yibi.backend.repository;

import com.yibi.backend.entity.LearningPath;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LearningPathRepository extends JpaRepository<LearningPath, Long> {
    Optional<LearningPath> findByUserId(UUID userId);
    boolean existsByUserId(UUID userId);
}
