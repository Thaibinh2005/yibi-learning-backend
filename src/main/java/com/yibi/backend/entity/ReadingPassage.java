package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "reading_passages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReadingPassage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "content_hanzi", nullable = false, columnDefinition = "TEXT")
    private String contentHanzi;

    @Column(name = "content_pinyin", columnDefinition = "TEXT")
    private String contentPinyin;

    @Column(name = "translation_vi", columnDefinition = "TEXT")
    private String translationVi;

    @Column(name = "hsk_level", nullable = false)
    private Integer hskLevel;

    /** Auto-calculated from contentHanzi length */
    @Column(name = "word_count")
    private Integer wordCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
