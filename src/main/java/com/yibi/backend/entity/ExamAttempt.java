package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Exam Attempt entity — maps to `exam_attempts` table.
 * Records a user's full exam session including submitted answers and score.
 *
 * answers JSONB format: {"questionId": "userAnswer", "2": "A", "3": "Beijing", ...}
 */
@Entity
@Table(name = "exam_attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    /** Score out of 100 (or exam-specific max), computed on submit */
    @Column(name = "score", precision = 5, scale = 2)
    private BigDecimal score;

    /** True if score ≥ passing threshold (usually 60%) */
    @Column(name = "passed")
    private Boolean passed;

    /**
     * User's submitted answers as JSONB.
     * Key: question ID (string), Value: selected option or text.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private String answers = "{}";

    @Column(name = "started_at", nullable = false)
    @Builder.Default
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** Wall-clock seconds from start to submit (not just active time) */
    @Column(name = "time_taken_seconds")
    private Integer timeTakenSeconds;
}
