package com.yibi.backend.dto.practice;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillMasteryStat {
    private String skillKey;        // vocabulary, phonetics, characters, grammar
    private String label;           // Tiêu đề tiếng Việt
    private int attemptsCount;
    private int correctCount;
    private double masteryScore;    // 0.0 to 1.0
    private double accuracyPercent; // 0.0 to 100.0
    private int dueReviewCount;     // Số mục cần ôn tập ngay
    private int correctStreak;
    private String lastPracticedAt; // ISO timestamp
}

