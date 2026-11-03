package com.yibi.backend.repository;

import com.yibi.backend.entity.SkillAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SkillAttemptRepository extends JpaRepository<SkillAttempt, Long> {
    List<SkillAttempt> findByUserIdOrderByAnsweredAtAsc(UUID userId);
}
