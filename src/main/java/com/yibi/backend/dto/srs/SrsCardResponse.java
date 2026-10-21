package com.yibi.backend.dto.srs;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SrsCardResponse {

    private Long cardId;
    private Long vocabularyId;
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
    private Integer repetition;
    private Integer intervalDays;
    private BigDecimal easeFactor;
    private LocalDate dueDate;
    private LocalDateTime lastReviewed;
}
