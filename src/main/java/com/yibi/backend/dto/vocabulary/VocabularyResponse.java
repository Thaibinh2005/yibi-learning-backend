package com.yibi.backend.dto.vocabulary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VocabularyResponse {

    private Long id;
    private String hanzi;
    private String hanziTraditional;
    private String pinyin;
    private String meaningVi;
    private String meaningEn;
    private Integer hskLevel;
    /** Source-preserved V6 level code, including the combined "7-9" source band. */
    private String levelCode;
    private String partOfSpeech;
    private String exampleSentence;
    private String examplePinyin;
    private String exampleTranslation;
    private String exampleAudioUrl;
    private String audioUrl;
    private Integer strokeCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** Measure words (量词) used with this noun; empty when none. */
    private java.util.List<com.yibi.backend.dto.vocabulary.MeasureWordResponse> measureWords;
}
