package com.yibi.backend.repository;

import com.yibi.backend.entity.PlacementAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlacementAssessmentRepository extends JpaRepository<PlacementAssessment, Long> {
    Optional<PlacementAssessment> findByIdAndUserId(Long id, UUID userId);
    Optional<PlacementAssessment> findFirstByUserIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(UUID userId);
}
