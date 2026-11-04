package com.yibi.backend.dto.placement;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlacementAnswerRequest {

    @NotNull(message = "Question id is required")
    private Long questionId;

    @NotBlank(message = "Choice is required")
    private String choiceKey;

    @Min(value = 0, message = "Response time cannot be negative")
    private Integer responseTimeSeconds;
}
