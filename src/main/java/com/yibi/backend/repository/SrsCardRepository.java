package com.yibi.backend.repository;

import com.yibi.backend.entity.SrsCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SrsCardRepository extends JpaRepository<SrsCard, Long> {

    /**
     * Core SRS query: get all cards due for review today or earlier.
     * This is the "build today's flashcard deck" query.
     * Indexed on (user_id, due_date) in PostgreSQL for performance.
     */
    @Query("SELECT c FROM SrsCard c WHERE c.user.id = :userId AND c.dueDate <= :today")
    List<SrsCard> findDueCards(@Param("userId") UUID userId, @Param("today") LocalDate today);

    Optional<SrsCard> findByUserIdAndVocabularyId(UUID userId, Long vocabularyId);

    boolean existsByUserIdAndVocabularyId(UUID userId, Long vocabularyId);

    /** Count of cards due today (for dashboard stats) */
    @Query("SELECT COUNT(c) FROM SrsCard c WHERE c.user.id = :userId AND c.dueDate <= :today")
    long countDueCards(@Param("userId") UUID userId, @Param("today") LocalDate today);

    /** Total cards in user's deck (for progress display) */
    long countByUserId(UUID userId);

    List<SrsCard> findByUserId(UUID userId);
}
