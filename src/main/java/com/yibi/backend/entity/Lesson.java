package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Lesson entity — maps to `lessons` table.
 *
 * Uses a polymorphic reference (contentRefId) instead of
 * foreign keys to keep the design flexible:
 *   VOCABULARY → vocabulary.id
 *   GRAMMAR    → grammar_points.id
 *   READING    → reading_passages.id
 *   LISTENING  → future audio content
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

    /** Polymorphic reference to the content entity's ID */
    @Column(name = "content_ref_id")
    private Long contentRefId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
