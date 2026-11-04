package com.yibi.backend.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiQuestionGenerationRequest {

    @NotBlank(message = "Level cannot be blank")
    private String levelCode;

    @NotBlank(message = "Skill cannot be blank")
    private String skillKey;

    @NotBlank(message = "Question type cannot be blank")
    private String questionType;

    /** Optional theme such as "gia đình" or "mua sắm"; the model stays within the level either way. */
    private String topicKey;

    @Min(value = 1, message = "Difficulty must be 1 to 5")
    @Max(value = 5, message = "Difficulty must be 1 to 5")
    private Integer difficulty;

    @Min(value = 1, message = "Count must be 1 to 20")
    @Max(value = 20, message = "Count must be 1 to 20")
    private Integer count;
}
