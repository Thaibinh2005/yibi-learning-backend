package com.yibi.backend.dto.srs;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** How well a learner wrote one HSK character; the card is created on the first rating. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SrsWritingRateRequest {

    @NotBlank(message = "Hanzi is required")
    @Size(max = 8, message = "Hanzi is too long")
    private String hanzi;

    @NotNull(message = "Rating is required (AGAIN, HARD, GOOD, EASY)")
    private SrsRateRequest.Rating rating;
}
