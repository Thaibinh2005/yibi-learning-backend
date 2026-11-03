package com.yibi.backend.dto.practice;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterySummaryResponse {
    private int totalAttempts;
    private int totalCorrect;
    private double overallAccuracy;
    private double averageMastery;
    private int dueReviewCount;
    private int bestStreak;
    private int learnedTopicsCount;
    private List<SkillMasteryStat> skills;
    private List<Map<String, Object>> levels;
    private List<Map<String, Object>> recentSessions;
}

