package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Grammar Point entity — maps to `grammar_points` table.
 *
 * The `examples` field is stored as JSONB in PostgreSQL.
 * JSON structure: [{"hanzi": "...", "pinyin": "...", "meaning_vi": "...", "meaning_en": "..."}]
 *
 * Data can be bulk-imported from LLM-generated JSON files via Admin CMS.
 */
@Entity
@Table(name = "grammar_points")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GrammarPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Short title, e.g., "把字句 (bǎ construction)" */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** Grammar pattern template, e.g., "Subject + 把 + Object + Verb + Complement" */
    @Column(name = "pattern", nullable = false, length = 300)
    private String pattern;

    /** Full explanation in Vietnamese/English */
    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    /**
     * Example sentences stored as JSONB array.
     * Mapped to String here; parse to List<Map> in service if needed.
     * Default: "[]"
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "examples", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private String examples = "[]";

    @Column(name = "hsk_level", nullable = false)
    private Integer hskLevel;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
