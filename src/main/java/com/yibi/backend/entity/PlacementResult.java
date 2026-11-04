package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** The stored outcome of one finished adaptive placement attempt. */
@Entity
@Table(name = "placement_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlacementResult {

    @Id
    @Column(name = "assessment_id")
    private Long assessmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @JdbcTypeCode(SqlTypes.SMALLINT)

    @Column(name = "estimated_level", nullable = false)
    private Integer estimatedLevel;

    @Column(name = "confidence", nullable = false, length = 8)
    private String confidence;

    @Column(name = "theta", nullable = false, precision = 6, scale = 3)
    private BigDecimal theta;

    @Column(name = "std_error", nullable = false, precision = 6, scale = 3)
    private BigDecimal stdError;

    @Column(name = "question_count", nullable = false)
    private Integer questionCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_scores", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> skillScores;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strengths", columnDefinition = "jsonb", nullable = false)
    private List<String> strengths;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weaknesses", columnDefinition = "jsonb", nullable = false)
    private List<String> weaknesses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weak_topics", columnDefinition = "jsonb", nullable = false)
    private List<String> weakTopics;

    @JdbcTypeCode(SqlTypes.SMALLINT)

    @Column(name = "recommended_level")
    private Integer recommendedLevel;

    @Column(name = "recommended_course_id")
    private Long recommendedCourseId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
