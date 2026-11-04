package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "placement_assessments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlacementAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "hsk_level", nullable = false)
    @Builder.Default
    private Integer hskLevel = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "question_snapshot", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> questionSnapshot;

    @Column(name = "score_percent", precision = 5, scale = 2)
    private BigDecimal scorePercent;

    @Column(name = "correct_count")
    private Integer correctCount;

    @Column(name = "total_questions", nullable = false)
    private Integer totalQuestions;

    @Column(name = "started_at", nullable = false)
    @Builder.Default
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** IN_PROGRESS, FINISHED or ABANDONED. */
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = "IN_PROGRESS";

    /** ADAPTIVE for the CAT test; LEGACY marks rows from the old fixed HSK 1 test. */
    @Column(name = "mode", nullable = false, length = 16)
    @Builder.Default
    private String mode = "ADAPTIVE";

    @Column(name = "theta", precision = 6, scale = 3)
    private BigDecimal theta;

    @Column(name = "std_error", precision = 6, scale = 3)
    private BigDecimal stdError;

    @JdbcTypeCode(SqlTypes.SMALLINT)

    @Column(name = "estimated_level")
    private Integer estimatedLevel;

    @Column(name = "confidence", length = 8)
    private String confidence;

    @Column(name = "algorithm_version", length = 24)
    private String algorithmVersion;
}
