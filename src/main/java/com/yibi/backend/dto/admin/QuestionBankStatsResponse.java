package com.yibi.backend.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionBankStatsResponse {

    private long totalQuestions;
    private long publishedCount;
    private long draftCount;
    private long withAudioCount;
    private Map<String, Long> byLevel;
    private Map<String, Long> byType;
    private Map<String, Long> bySkill;
}

