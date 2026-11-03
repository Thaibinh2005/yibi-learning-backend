package com.yibi.backend.dto.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Summary returned by the generator run (both dry-run and import modes). */
@Data
@Builder
public class GeneratorRunResult {

    private String mode;
    private String levelCode;
    private String questionType;
    private int requestedLimit;

    /** Number of questions that passed the quality gate. */
    private int generated;

    /** Number of raw records skipped due to missing/ambiguous data or postponed type. */
    private int skipped;

    /** Number of questions that failed quality gate checks. */
    private int failedGate;

    /** Number of questions successfully written to DB (import mode only). */
    private int imported;

    /** Number of questions already existing in DB (content_hash collision). */
    private int alreadyExisted;

    /** Number of questions that failed during DB insert (import mode only). */
    private int failedInsert;

    private List<GeneratedQuestionPreview> questions;
}
