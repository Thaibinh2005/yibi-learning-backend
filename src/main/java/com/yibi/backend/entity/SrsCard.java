package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * SRS Card entity — maps to `srs_cards` table.
 *
 * Implements the SM-2 (SuperMemo 2) Spaced Repetition algorithm.
 *
 * Algorithm fields:
 *   - easeFactor (EF): determines how quickly intervals grow.
 *     Initial: 2.5. Min: 1.3. Updated per review based on quality score.
 *   - intervalDays (I): days until next review.
 *     I(1) = 1 day, I(2) = 6 days, I(n) = I(n-1) * EF
 *   - repetitions (n): consecutive successful reviews count.
 *     Reset to 0 on poor performance (quality < 3).
 *   - dueDate: computed = lastReviewed + intervalDays.
 *     Indexed for efficient "get today's deck" queries.
 *
 * Quality scores (0-5):
 *   0-2 → fail (reset repetitions to 0, interval to 1)
 *   3-5 → pass (advance interval, update EF)
 */
@Entity
@Table(name = "srs_cards",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "vocabulary_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SrsCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER)  // Load vocabulary eagerly (needed for flashcard display)
    @JoinColumn(name = "vocabulary_id", nullable = false)
    private Vocabulary vocabulary;

    /** SM-2: Ease Factor — starts at 2.5, minimum 1.3 */
    @Column(name = "ease_factor", nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal easeFactor = new BigDecimal("2.50");

    /** SM-2: Interval in days until next review */
    @Column(name = "interval_days", nullable = false)
    @Builder.Default
    private Integer intervalDays = 1;

    /** SM-2: Consecutive successful repetitions count */
    @Column(name = "repetitions", nullable = false)
    @Builder.Default
    private Integer repetitions = 0;

    /** Date this card is next due for review (indexed) */
    @Column(name = "due_date", nullable = false)
    @Builder.Default
    private LocalDate dueDate = LocalDate.now();

    @Column(name = "last_reviewed")
    private LocalDateTime lastReviewed;
}
