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
    private String partOfSpeech;
    private String exampleSentence;
    private String examplePinyin;
    private String audioUrl;
    private Integer strokeCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
