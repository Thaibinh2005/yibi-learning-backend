package com.yibi.backend.repository;

import com.yibi.backend.entity.SpeechRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SpeechRecordRepository extends JpaRepository<SpeechRecord, Long> {

    List<SpeechRecord> findByUserIdOrderByRecordedAtDesc(UUID userId);

    /** Average pronunciation score for a user */
    @Query("SELECT AVG(s.accuracyScore) FROM SpeechRecord s WHERE s.user.id = :userId AND s.accuracyScore IS NOT NULL")
    Double findAverageScoreByUserId(@Param("userId") UUID userId);
}
