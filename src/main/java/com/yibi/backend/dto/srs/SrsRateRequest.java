package com.yibi.backend.dto.srs;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Rate a vocabulary word with one of four buttons (topic flashcards).
 * The word is added to the user's SRS deck automatically if missing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SrsRateRequest {

    @NotNull(message = "Vocabulary ID is required")
    private Long vocabularyId;

    @NotNull(message = "Rating is required (AGAIN, HARD, GOOD, EASY)")
    private Rating rating;

    public enum Rating {
        /** Học lại — again in ~10 minutes (due today). */
        AGAIN,
        /** Khó — 1 to 3 days. */
        HARD,
        /** Tốt — 1 to 4 days. */
        GOOD,
        /** Dễ — 1 to 7 days. */
        EASY
    }
}
