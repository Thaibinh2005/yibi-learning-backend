package com.yibi.backend.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Parameters for the admin question-bank generator endpoint.
 *
 * <p>Supported question types:
 * <ul>
 *   <li>PHONETICS_SELECT – display Hanzi, choose correct pinyin</li>
 *   <li>MEANING_HANZI    – display pinyin/meaning, choose correct Hanzi</li>
 *   <li>VOCAB_MEANING    – display Hanzi, choose correct Vietnamese meaning</li>
 *   <li>CLOZE            – fill in the blank in a sentence</li>
 *   <li>GRAMMAR_SELECT   – choose the correct grammar structure</li>
 * </ul>
 */
@Data
public class QuestionGeneratorParams {

    /** HSK level: 1, 2, 3, 4, 5, 6, or 7-9. */
    @NotBlank
    @Pattern(regexp = "^(1|2|3|4|5|6|7-9)$", message = "level_code must be 1–6 or 7-9")
    private String levelCode = "1";

    /**
     * Question type to generate.
     * PHONETICS_SELECT | MEANING_HANZI | VOCAB_MEANING | CLOZE | GRAMMAR_SELECT
     */
    @NotBlank
    @Pattern(
        regexp = "^(PHONETICS_SELECT|MEANING_HANZI|VOCAB_MEANING|CLOZE|GRAMMAR_SELECT)$",
        message = "question_type must be one of the five supported types"
    )
    private String questionType = "PHONETICS_SELECT";

    /** Maximum number of questions to generate (1–200). */
    @Min(1)
    @Max(200)
    private int limit = 50;

    /**
     * Run mode.
     * <ul>
     *   <li>{@code dry-run} – preview only, no DB writes</li>
     *   <li>{@code import}  – persist to question_bank_items/choices/sources</li>
     * </ul>
     */
    @Pattern(regexp = "^(dry-run|import)$", message = "mode must be 'dry-run' or 'import'")
    private String mode = "dry-run";
}

