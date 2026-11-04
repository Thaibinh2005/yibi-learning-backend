package com.yibi.backend.service.cat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Computerized adaptive test on a 2PL IRT model: P(correct) = 1 / (1 + e^(-a(theta - b))).
 * Ability is the posterior mean (EAP) on a 0.1 grid, the next question is the one with the most
 * information at the current estimate, and the test stops on length and standard error.
 * Holds no state between calls and touches no database.
 */
public class CatEngine {

    public static final String ALGORITHM_VERSION = "cat-2pl-eap-v1";

    private static final double GRID_MIN = 0.0;
    private static final double GRID_MAX = 10.0;
    private static final double GRID_STEP = 0.1;
    private static final int GRID_SIZE = (int) Math.round((GRID_MAX - GRID_MIN) / GRID_STEP) + 1;
    private static final double SKILL_PRIOR_SD = 1.5;
    private static final double STRENGTH_GAP = 0.5;

    private final CatConfig config;
    private final Random random;

    public CatEngine(CatConfig config, Random random) {
        this.config = config;
        this.random = random;
    }

    /** Ability after the given answers; with none, the prior itself. */
    public CatEstimate estimate(List<CatResponse> responses, double priorMean) {
        return posterior(responses, priorMean, config.priorStandardDeviation());
    }

    /** The unanswered question with the most information, or empty when none is left. */
    public Optional<CatItem> nextItem(List<CatItem> pool, List<CatResponse> responses, CatEstimate estimate) {
        Set<Long> answered = new HashSet<>();
        Map<String, Integer> perSkill = new HashMap<>();
        for (CatResponse r : responses) {
            answered.add(r.item().id());
            perSkill.merge(r.item().skill(), 1, Integer::sum);
        }
        List<CatItem> candidates = new ArrayList<>();
        for (CatItem item : pool) {
            if (!answered.contains(item.id())) candidates.add(item);
        }
        if (candidates.isEmpty()) return Optional.empty();

        List<CatItem> restricted = restrictToMissingSkills(candidates, perSkill, responses.size());
        restricted.sort(Comparator.comparingDouble((CatItem item) -> information(item, estimate.theta())).reversed());
        int top = Math.min(config.randomesque(), restricted.size());
        return Optional.of(restricted.get(top <= 1 ? 0 : random.nextInt(top)));
    }

    /** Stops at the maximum length, or after the minimum once the estimate is precise and every skill has its share. */
    public boolean shouldStop(List<CatResponse> responses, CatEstimate estimate, List<CatItem> pool) {
        int answered = responses.size();
        if (answered >= config.maxQuestions()) return true;
        Set<Long> used = new HashSet<>();
        for (CatResponse r : responses) used.add(r.item().id());
        boolean anyLeft = pool.stream().anyMatch(item -> !used.contains(item.id()));
        if (!anyLeft) return true;
        if (answered < config.minQuestions() || estimate.standardError() > config.targetStandardError()) return false;
        return missingSkills(pool, used, countPerSkill(responses)).isEmpty();
    }

    /** The outcome of a finished test. */
    public CatResult finish(List<CatResponse> responses, CatEstimate estimate, double priorMean) {
        List<CatSkillScore> scores = new ArrayList<>();
        Map<String, List<CatResponse>> bySkill = new LinkedHashMap<>();
        for (CatResponse r : responses) bySkill.computeIfAbsent(r.item().skill(), k -> new ArrayList<>()).add(r);
        List<String> strengths = new ArrayList<>();
        List<String> weaknesses = new ArrayList<>();
        for (Map.Entry<String, List<CatResponse>> e : bySkill.entrySet()) {
            List<CatResponse> list = e.getValue();
            if (list.size() < config.minAttemptsForSkillScore()) continue;
            CatEstimate skillEstimate = posterior(list, estimate.theta(), SKILL_PRIOR_SD);
            int correct = (int) list.stream().filter(CatResponse::correct).count();
            scores.add(new CatSkillScore(e.getKey(), list.size(), correct, skillEstimate.theta(), skillEstimate.standardError()));
            double gap = skillEstimate.theta() - estimate.theta();
            if (gap >= STRENGTH_GAP) strengths.add(e.getKey());
            if (gap <= -STRENGTH_GAP) weaknesses.add(e.getKey());
        }
        return new CatResult(levelOf(estimate.theta()), confidenceOf(estimate.standardError()), estimate.theta(),
                estimate.standardError(), responses.size(), scores, strengths, weaknesses, weakTopics(responses));
    }

    public static int levelOf(double theta) {
        return (int) Math.max(1, Math.min(9, Math.round(theta)));
    }

    public static CatResult.Confidence confidenceOf(double standardError) {
        if (standardError < 0.4) return CatResult.Confidence.HIGH;
        if (standardError < 0.7) return CatResult.Confidence.MEDIUM;
        return CatResult.Confidence.LOW;
    }

    /** P(correct) under the 2PL model. */
    public static double probability(CatItem item, double theta) {
        return 1.0 / (1.0 + Math.exp(-item.a() * (theta - item.b())));
    }

    /** Fisher information of a question at an ability: a^2 * P * (1 - P). */
    public static double information(CatItem item, double theta) {
        double p = probability(item, theta);
        return item.a() * item.a() * p * (1.0 - p);
    }

    private CatEstimate posterior(List<CatResponse> responses, double priorMean, double priorSd) {
        double[] logPost = new double[GRID_SIZE];
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < GRID_SIZE; i++) {
            double theta = GRID_MIN + i * GRID_STEP;
            double z = (theta - priorMean) / priorSd;
            double lp = -0.5 * z * z;
            for (CatResponse r : responses) {
                double p = clamp(probability(r.item(), theta));
                lp += Math.log(r.correct() ? p : 1.0 - p);
            }
            logPost[i] = lp;
            max = Math.max(max, lp);
        }
        double sum = 0, mean = 0;
        double[] weight = new double[GRID_SIZE];
        for (int i = 0; i < GRID_SIZE; i++) {
            weight[i] = Math.exp(logPost[i] - max);
            sum += weight[i];
            mean += weight[i] * (GRID_MIN + i * GRID_STEP);
        }
        mean /= sum;
        double variance = 0;
        for (int i = 0; i < GRID_SIZE; i++) {
            double d = GRID_MIN + i * GRID_STEP - mean;
            variance += weight[i] * d * d;
        }
        return new CatEstimate(mean, Math.sqrt(variance / sum));
    }

    /** Once the minimum length leaves no room for the skills still short of their share, only those skills are offered. */
    private List<CatItem> restrictToMissingSkills(List<CatItem> candidates, Map<String, Integer> perSkill, int answered) {
        Set<Long> none = Set.of();
        Map<String, Integer> missing = missingSkills(candidates, none, perSkill);
        int needed = missing.values().stream().mapToInt(Integer::intValue).sum();
        int slack = config.minQuestions() - answered;
        if (needed == 0 || needed < slack) return new ArrayList<>(candidates);
        List<CatItem> restricted = new ArrayList<>();
        for (CatItem item : candidates) if (missing.containsKey(item.skill())) restricted.add(item);
        return restricted;
    }

    /** Skills that still have questions available but fewer than the minimum answers, with how many are missing. */
    private Map<String, Integer> missingSkills(List<CatItem> pool, Set<Long> used, Map<String, Integer> perSkill) {
        Map<String, Integer> missing = new HashMap<>();
        for (CatItem item : pool) {
            if (used.contains(item.id())) continue;
            int have = perSkill.getOrDefault(item.skill(), 0);
            if (have < config.minPerSkill()) missing.put(item.skill(), config.minPerSkill() - have);
        }
        return missing;
    }

    private Map<String, Integer> countPerSkill(List<CatResponse> responses) {
        Map<String, Integer> counts = new HashMap<>();
        for (CatResponse r : responses) counts.merge(r.item().skill(), 1, Integer::sum);
        return counts;
    }

    private List<String> weakTopics(List<CatResponse> responses) {
        Map<String, Integer> wrong = new HashMap<>();
        for (CatResponse r : responses) {
            if (!r.correct() && r.item().topic() != null && !r.item().topic().isBlank()) {
                wrong.merge(r.item().topic(), 1, Integer::sum);
            }
        }
        return wrong.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(5).map(Map.Entry::getKey).toList();
    }

    private static double clamp(double p) {
        return Math.max(1e-9, Math.min(1.0 - 1e-9, p));
    }
}
