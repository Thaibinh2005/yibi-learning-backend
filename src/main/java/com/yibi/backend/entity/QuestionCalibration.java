package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** IRT 2PL parameters of one question bank item; ESTIMATED until calibrated from real answers. */
@Entity
@Table(name = "question_calibration")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuestionCalibration {

    @Id
    @Column(name = "question_id")
    private Long questionId;

    @Column(name = "irt_a", nullable = false, precision = 5, scale = 3)
    @Builder.Default
    private BigDecimal irtA = BigDecimal.ONE;

    @Column(name = "irt_b", nullable = false, precision = 6, scale = 3)
    private BigDecimal irtB;

    /** ESTIMATED or CALIBRATED. */
    @Column(name = "source", nullable = false, length = 16)
    @Builder.Default
    private String source = "ESTIMATED";

    @Column(name = "sample_size", nullable = false)
    @Builder.Default
    private Integer sampleSize = 0;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
