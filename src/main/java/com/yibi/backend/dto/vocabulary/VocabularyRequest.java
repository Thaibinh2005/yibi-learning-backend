package com.yibi.backend.dto.vocabulary;

import jakarta.validation.constraints.Max;
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
public class VocabularyRequest {

    @NotBlank(message = "Hanzi word is required")
    private String hanzi;

    private String hanziTraditional;

    @NotBlank(message = "Pinyin is required")
    private String pinyin;

    private String meaningVi;

    private String meaningEn;

    @NotNull(message = "HSK level is required (1-6)")
    @Min(value = 1, message = "HSK level must be between 1 and 6")
    @Max(value = 6, message = "HSK level must be between 1 and 6")
    private Integer hskLevel;

    private String partOfSpeech;

    private String exampleSentence;

    private String examplePinyin;

    private String audioUrl;

    private Integer strokeCount;
}
