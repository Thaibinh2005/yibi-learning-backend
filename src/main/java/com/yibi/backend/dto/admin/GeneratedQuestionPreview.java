package com.yibi.backend.dto.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A single generated question returned by the generator (dry-run or import mode).
 * Carries enough information for a human reviewer to verify quality.
 */
@Data
@Builder
public class GeneratedQuestionPreview {

    /** Stable code that will be stored in question_bank_items.question_code. */
    private String questionCode;

    /** Question type (PHONETICS_SELECT, VOCAB_MEANING, …). */
    private String questionType;

    /** HSK level code (1–6, 7-9). */
    private String levelCode;

    /** Skill key (vocabulary, phonetics, grammar, characters). */
    private String skillKey;

    /** Topic key (e.g. pronunciation_general, vocabulary_general). */
    private String topicKey;

    /** The prompt shown to the learner. */
    private String prompt;

    /** Pinyin for the prompt when available. */
    private String promptPinyin;

    /** The correct answer text (matches one of the choices). */
    private String correctAnswer;

    /** All four choices (A–D). */
    private List<ChoicePreview> choices;

    /** Brief explanation of why the correct answer is right. */
    private String explanation;

    /** raw_record_id of the HSK source row used. */
    private Long sourceRawRecordId;

    /** Result of quality gate. */
    private String validationStatus;

    /** Reason for skipping this item (null when valid). */
    private String skipReason;

    @Data
    @Builder
    public static class ChoicePreview {
        private String key;       // A, B, C, D
        private String text;
        private boolean correct;
    }
}

