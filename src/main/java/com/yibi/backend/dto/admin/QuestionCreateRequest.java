package com.yibi.backend.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionCreateRequest {

    @NotBlank(message = "Question type cannot be blank")
    private String questionType;

    @NotBlank(message = "Skill cannot be blank")
    private String skillKey;

    @NotBlank(message = "Level cannot be blank")
    private String levelCode;

    private String topicKey;

    @NotBlank(message = "Prompt cannot be blank")
    private String prompt;

    private String promptPinyin;
    private String promptAudioUrl;
    private String explanation;
    private Integer difficulty;

    /** The text to read aloud; used by READ_ALOUD, which has no choices. */
    private String answerText;

    /** HUMAN (default) or AI_GENERATED. */
    private String origin;

    private List<QuestionUpdateRequest.ChoiceUpdateRequest> choices;
}
