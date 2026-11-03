package com.yibi.backend.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionUpdateRequest {

    @NotBlank(message = "Prompt cannot be blank")
    private String prompt;

    private String promptPinyin;
    private String promptAudioUrl;
    private String explanation;
    private Integer difficulty;
    private String status; // DRAFT, PUBLISHED, REVIEWED, RETIRED

    @NotEmpty(message = "Choices cannot be empty")
    private List<ChoiceUpdateRequest> choices;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChoiceUpdateRequest {
        private String key; // A, B, C, D
        private String text;
        private boolean correct;
    }
}

