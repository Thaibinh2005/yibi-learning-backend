package com.yibi.backend.service.plan;

/**
 * What a target score means. HSK 1-2 are out of 200 and HSK 3 and above out of 300; HSKK is out of 100.
 * A higher target raises the bar for tests on the path, but never past what a good learner can reach on a short test.
 */
public final class ScoreGoal {

    public static final int BASE_PASS_PERCENT = 80;
    public static final int HIGHEST_PASS_PERCENT = 95;

    private ScoreGoal() { }

    /** The scale of a goal, or 0 when the goal has no score, as with communication. */
    public static int scale(String goalType, int targetLevel) {
        if ("COMMUNICATION".equals(goalType)) return 0;
        if ("HSKK".equals(goalType)) return 100;
        return scaleOfLevel(targetLevel);
    }

    public static int scaleOfLevel(int level) {
        return level <= 2 ? 200 : 300;
    }

    public static int passPercent(String goalType, int targetLevel, Integer targetScore) {
        int scale = scale(goalType, targetLevel);
        if (scale == 0 || targetScore == null) return BASE_PASS_PERCENT;
        int wanted = (int) Math.ceil(targetScore * 100.0 / scale);
        return Math.max(BASE_PASS_PERCENT, Math.min(HIGHEST_PASS_PERCENT, wanted));
    }

    public static int estimatedScore(int percent, int scale) {
        return Math.round(percent * scale / 100f);
    }
}
