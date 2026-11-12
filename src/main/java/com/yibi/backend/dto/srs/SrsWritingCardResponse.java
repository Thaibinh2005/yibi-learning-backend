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
public class SrsWritingCardResponse {

    private Long cardId;
    private String hanzi;
    private String pinyin;
    private Integer repetition;
    private Integer intervalDays;
    private BigDecimal easeFactor;
    private LocalDate dueDate;
    private LocalDateTime lastReviewed;
}
