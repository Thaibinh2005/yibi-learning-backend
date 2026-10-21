package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Question entity — maps to `questions` table.
 *
 * options JSONB format: {"A": "选项A", "B": "选项B", "C": "选项C", "D": "选项D"}
 * correctAnswer: "A" | "B" | "C" | "D" (for MULTIPLE_CHOICE)
 *               plain text (for FILL_BLANK)
 */
@Entity
@Table(name = "questions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 20)
    private QuestionType questionType;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Audio URL for LISTENING type questions */
    @Column(name = "audio_url", length = 500)
    private String audioUrl;

    /**
     * Answer options stored as JSONB.
     * For MULTIPLE_CHOICE: {"A": "...", "B": "...", "C": "...", "D": "..."}
     * For FILL_BLANK/LISTENING: {} (empty)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private String options = "{}";

    @Column(name = "correct_answer", nullable = false, length = 10)
    private String correctAnswer;

    @Column(name = "hsk_level")
    private Integer hskLevel;
}
