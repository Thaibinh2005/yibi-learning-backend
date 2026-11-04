package com.yibi.backend.repository;

import com.yibi.backend.entity.PlacementResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlacementResultRepository extends JpaRepository<PlacementResult, Long> {
    Optional<PlacementResult> findByAssessmentIdAndUserId(Long assessmentId, UUID userId);
}
