package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "skill_attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkillAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false)
    private PlacementAssessment assessment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "question_key", nullable = false, length = 36)
    private String questionKey;

    @Column(name = "skill_key", nullable = false, length = 40)
    private String skillKey;

    @Column(name = "topic_key", length = 300)
    private String topicKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id")
    private Lesson lesson;

    @Column(name = "is_correct", nullable = false)
    private Boolean correct;

    @Column(name = "response_time_seconds", nullable = false)
    @Builder.Default
    private Integer responseTimeSeconds = 0;

    @Column(name = "answered_at", nullable = false)
    @Builder.Default
    private LocalDateTime answeredAt = LocalDateTime.now();

    /** The question bank is read through JdbcTemplate, so these two stay plain ids. */
    @Column(name = "question_id")
    private Long questionId;

    @Column(name = "selected_choice_id")
    private Long selectedChoiceId;

    @JdbcTypeCode(SqlTypes.SMALLINT)

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "theta_before", precision = 6, scale = 3)
    private BigDecimal thetaBefore;
}
