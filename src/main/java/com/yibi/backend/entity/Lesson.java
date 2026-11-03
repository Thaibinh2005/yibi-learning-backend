package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Lesson entity — maps to `lessons` table.
 *
 * contentRefId points to an exact raw source row in the V6 HSK dataset.
 * lessonPayload retains the complete imported lesson material.
 */
@Entity
@Table(name = "lessons")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "lesson_order", nullable = false)
    @Builder.Default
    private Integer lessonOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private LessonContentType contentType;

    /** Optional exact V6 source record ID selected for this lesson. */
    @Column(name = "content_ref_id")
    private Long contentRefId;

    /** Complete versioned ETL JSON (source + all lesson sections). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lesson_payload", columnDefinition = "jsonb")
    private Map<String, Object> lessonPayload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
