package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "learning_paths")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningPath {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** One learning path per user (enforced by UNIQUE constraint) */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /** Determined by Placement Test result */
    @Column(name = "current_hsk_level", nullable = false)
    @Builder.Default
    private Integer currentHskLevel = 1;

    /** User's study goal */
    @Column(name = "target_hsk_level", nullable = false)
    @Builder.Default
    private Integer targetHskLevel = 6;

    /** Suggested daily vocabulary count (adaptive based on level gap) */
    @Column(name = "recommended_daily_words", nullable = false)
    @Builder.Default
    private Integer recommendedDailyWords = 10;

    /** What the learner studies for: HSK, HSKK or COMMUNICATION */
    @Column(name = "goal_type", nullable = false, length = 20)
    @Builder.Default
    private String goalType = "HSK";

    /** Optional exam score the learner aims for */
    @Column(name = "target_score")
    private Integer targetScore;

    /** Optional date the learner wants to reach the goal by */
    @Column(name = "target_date")
    private java.time.LocalDate targetDate;

    /** Minutes the learner plans to study each day; sizes the daily plan */
    @Column(name = "daily_minutes", nullable = false)
    @Builder.Default
    private Integer dailyMinutes = 20;

    /** Timestamp of last path generation / recalculation */
    @Column(name = "generated_at", nullable = false)
    @Builder.Default
    private LocalDateTime generatedAt = LocalDateTime.now();
}
