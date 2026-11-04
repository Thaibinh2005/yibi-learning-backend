package com.yibi.backend.service.cat;

import java.util.List;

/** What a finished test reports. Skills with too few answers are left out of the lists, never scored. */
public record CatResult(
        int level,
        Confidence confidence,
        double theta,
        double standardError,
        int questionCount,
        List<CatSkillScore> skillScores,
        List<String> strengths,
        List<String> weaknesses,
        List<String> weakTopics) {

    public enum Confidence { LOW, MEDIUM, HIGH }
}
