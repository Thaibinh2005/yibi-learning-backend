package com.yibi.backend.dto.vocabulary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyVocabularyResponse {

    private Long id;
    private String hanzi;
    private String hanziTraditional;
    private String pinyin;
    private String meaningVi;
    private List<String> meanings;
    private Integer hskLevel;
    private String levelCode;
    private String partOfSpeech;
    /** Vietnamese-readable part-of-speech label, e.g. "Danh từ", "Động từ" */
    private String partOfSpeechVi;
    private String audioUrl;
    private LocalDate date;
    private String dateLabel;
    private Boolean isToday;

    /** Example sentences for this word */
    private List<VocabularyExampleResponse> examples;

    /** Measure words (量词) used with this noun; empty when none. */
    private java.util.List<com.yibi.backend.dto.vocabulary.MeasureWordResponse> measureWords;
}
