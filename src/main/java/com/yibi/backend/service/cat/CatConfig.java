package com.yibi.backend.service.cat;

/**
 * Tuning of the adaptive test. These are project decisions, not published values:
 * they travel with {@link CatEngine#ALGORITHM_VERSION} so results can be recomputed if they change.
 */
public record CatConfig(
        int minQuestions,
        int maxQuestions,
        double targetStandardError,
        double priorStandardDeviation,
        int minPerSkill,
        int randomesque,
        int minAttemptsForSkillScore) {

    public static CatConfig defaults() {
        return new CatConfig(15, 30, 0.5, 3.0, 2, 3, 3);
    }
}
