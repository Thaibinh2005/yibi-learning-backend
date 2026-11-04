package com.yibi.backend.dto.placement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlacementResultResponse {

    private Long attemptId;
    /** Estimated HSK level, 1 to 9. */
    private int level;
    /** LOW, MEDIUM or HIGH. */
    private String confidence;
    private double theta;
    private double standardError;
    private int questionCount;
    private int recommendedLevel;
    /** Only skills with enough answers to score. */
    private List<SkillScore> skillScores;
    private List<String> strengths;
    private List<String> weaknesses;
    private List<String> weakTopics;
    private Long recommendedCourseId;
    private String recommendedCourseTitle;
    /** How many of the missed words are now in the learner's review deck. */
    private int reviewCardsAdded;
    /** Syllabus words behind the questions the learner got wrong. */
    private List<MissedWord> missedWords;

    @Data
    @AllArgsConstructor
    public static class MissedWord {
        private String word;
        private String pinyin;
        private boolean inDeck;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillScore {
        private String skill;
        private String label;
        private int attempts;
        private int correct;
        private double theta;
        /** The HSK level this skill alone suggests, 1 to 9. */
        private int level;
    }
}
