package com.yibi.backend.service.cat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CatEngineTest {

    private static CatItem item(long id, String skill, double b) {
        return new CatItem(id, skill, "topic-" + id, 1.0, b);
    }

    private static CatResponse answer(CatItem item, boolean correct) {
        return new CatResponse(item, correct);
    }

    private static CatConfig config(int min, int max, int minPerSkill, int randomesque) {
        return new CatConfig(min, max, 0.5, 3.0, minPerSkill, randomesque, 3);
    }

    @Test
    void withNoAnswersTheEstimateIsTheWidePrior() {
        CatEngine engine = new CatEngine(CatConfig.defaults(), new Random(1));
        CatEstimate estimate = engine.estimate(List.of(), 3.0);
        assertThat(estimate.theta()).isBetween(2.0, 4.5);
        assertThat(estimate.standardError()).isGreaterThan(2.0);
    }

    @Test
    void correctAnswersOnHardQuestionsRaiseTheEstimateAndWrongOnesLowerIt() {
        CatEngine engine = new CatEngine(CatConfig.defaults(), new Random(1));
        List<CatResponse> up = new ArrayList<>();
        List<CatResponse> down = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            up.add(answer(item(i, "grammar", 6.0), true));
            down.add(answer(item(100 + i, "grammar", 2.0), false));
        }
        assertThat(engine.estimate(up, 3.0).theta()).isGreaterThan(5.0);
        assertThat(engine.estimate(down, 3.0).theta()).isLessThan(2.0);
    }

    @Test
    void everyAnswerNarrowsTheStandardError() {
        CatEngine engine = new CatEngine(CatConfig.defaults(), new Random(1));
        List<CatResponse> responses = new ArrayList<>();
        double previous = engine.estimate(responses, 3.0).standardError();
        for (int i = 0; i < 10; i++) {
            responses.add(answer(item(i, "grammar", 3.0 + (i % 2)), i % 2 == 0));
            double now = engine.estimate(responses, 3.0).standardError();
            assertThat(now).isLessThan(previous);
            previous = now;
        }
    }

    @Test
    void picksTheQuestionNearestTheEstimate() {
        CatEngine engine = new CatEngine(config(1, 30, 0, 1), new Random(1));
        List<CatItem> pool = List.of(item(1, "a", 1), item(2, "a", 3), item(3, "a", 5), item(4, "a", 7), item(5, "a", 9));
        Optional<CatItem> next = engine.nextItem(pool, List.of(), new CatEstimate(5.1, 1.0));
        assertThat(next).get().extracting(CatItem::id).isEqualTo(3L);
    }

    @Test
    void neverOffersAnAnsweredQuestionAndRunsOut() {
        CatEngine engine = new CatEngine(config(1, 30, 0, 3), new Random(1));
        CatItem only = item(1, "a", 3);
        List<CatResponse> responses = List.of(answer(only, true));
        assertThat(engine.nextItem(List.of(only), responses, new CatEstimate(3, 1))).isEmpty();
    }

    @Test
    void offersOnlyAMissingSkillWhenNoRoomIsLeftForIt() {
        CatEngine engine = new CatEngine(config(1, 4, 2, 1), new Random(1));
        List<CatItem> pool = new ArrayList<>(List.of(item(1, "listening", 3), item(2, "listening", 3), item(3, "listening", 3)));
        pool.add(item(10, "speaking", 8));
        pool.add(item(11, "speaking", 8));
        List<CatResponse> responses = List.of(answer(pool.get(0), true), answer(pool.get(1), true));
        Optional<CatItem> next = engine.nextItem(pool, responses, new CatEstimate(3, 1));
        assertThat(next).get().extracting(CatItem::skill).isEqualTo("speaking");
    }

    @Test
    void stopRules() {
        CatEngine engine = new CatEngine(config(3, 5, 0, 1), new Random(1));
        List<CatItem> pool = new ArrayList<>();
        for (int i = 0; i < 10; i++) pool.add(item(i, "a", 3));
        List<CatResponse> two = List.of(answer(pool.get(0), true), answer(pool.get(1), true));
        List<CatResponse> three = List.of(answer(pool.get(0), true), answer(pool.get(1), true), answer(pool.get(2), true));
        List<CatResponse> five = new ArrayList<>(three);
        five.add(answer(pool.get(3), true));
        five.add(answer(pool.get(4), true));

        assertThat(engine.shouldStop(two, new CatEstimate(3, 0.1), pool)).as("before the minimum").isFalse();
        assertThat(engine.shouldStop(three, new CatEstimate(3, 0.9), pool)).as("not precise enough").isFalse();
        assertThat(engine.shouldStop(three, new CatEstimate(3, 0.4), pool)).as("precise at the minimum").isTrue();
        assertThat(engine.shouldStop(five, new CatEstimate(3, 0.9), pool)).as("maximum reached").isTrue();
        assertThat(engine.shouldStop(two, new CatEstimate(3, 0.9), List.of(pool.get(0), pool.get(1)))).as("pool empty").isTrue();
    }

    @Test
    void doesNotStopWhileASkillStillLacksItsMinimum() {
        CatEngine engine = new CatEngine(config(2, 30, 2, 1), new Random(1));
        List<CatItem> pool = List.of(item(1, "a", 3), item(2, "a", 3), item(3, "b", 3), item(4, "b", 3));
        List<CatResponse> responses = List.of(answer(pool.get(0), true), answer(pool.get(1), true));
        assertThat(engine.shouldStop(responses, new CatEstimate(3, 0.1), pool)).isFalse();
    }

    @Test
    void levelAndConfidenceFollowTheEstimate() {
        assertThat(CatEngine.levelOf(0.2)).isEqualTo(1);
        assertThat(CatEngine.levelOf(3.4)).isEqualTo(3);
        assertThat(CatEngine.levelOf(3.6)).isEqualTo(4);
        assertThat(CatEngine.levelOf(9.8)).isEqualTo(9);
        assertThat(CatEngine.confidenceOf(0.39)).isEqualTo(CatResult.Confidence.HIGH);
        assertThat(CatEngine.confidenceOf(0.5)).isEqualTo(CatResult.Confidence.MEDIUM);
        assertThat(CatEngine.confidenceOf(0.7)).isEqualTo(CatResult.Confidence.LOW);
    }

    @Test
    void finishNamesStrengthsWeaknessesAndTopicsAndSkipsThinSkills() {
        CatEngine engine = new CatEngine(CatConfig.defaults(), new Random(1));
        List<CatResponse> responses = new ArrayList<>();
        long id = 0;
        for (int i = 0; i < 4; i++) responses.add(answer(item(id++, "listening", 4), true));
        for (int i = 0; i < 4; i++) responses.add(answer(new CatItem(id++, "grammar", "ba-sentence", 1.0, 4), false));
        for (int i = 0; i < 4; i++) responses.add(answer(item(id++, "vocabulary", 4), i < 2));
        for (int i = 0; i < 2; i++) responses.add(answer(item(id++, "speaking", 4), true));

        CatEstimate estimate = engine.estimate(responses, 3.0);
        CatResult result = engine.finish(responses, estimate, 3.0);

        assertThat(result.questionCount()).isEqualTo(14);
        assertThat(result.strengths()).containsExactly("listening");
        assertThat(result.weaknesses()).containsExactly("grammar");
        assertThat(result.weakTopics()).first().isEqualTo("ba-sentence");
        assertThat(result.skillScores()).extracting(CatSkillScore::skill)
                .containsExactlyInAnyOrder("listening", "grammar", "vocabulary")
                .doesNotContain("speaking");
    }

    /** Simulated learners with a known ability: the test must find it in 15 to 30 questions without repeats. */
    @Test
    void simulatedLearnersAreMeasuredCloseToTheirTrueAbility() {
        Random world = new Random(2024);
        List<String> skills = List.of("listening", "reading", "vocabulary", "grammar", "characters", "writing");
        double[] bands = {1, 2, 3, 4, 5, 6, 8};
        List<CatItem> pool = new ArrayList<>();
        long id = 0;
        for (double band : bands) {
            for (String skill : skills) {
                for (int k = 0; k < 10; k++) {
                    pool.add(item(id++, skill, band + (k % 5 - 2) * 0.25));
                }
            }
        }

        int learners = 300;
        double totalError = 0;
        int totalQuestions = 0;
        for (int n = 0; n < learners; n++) {
            double trueTheta = 1.5 + world.nextDouble() * 6.0;
            CatEngine engine = new CatEngine(CatConfig.defaults(), new Random(n));
            List<CatResponse> responses = new ArrayList<>();
            CatEstimate estimate = engine.estimate(responses, 3.0);
            while (!engine.shouldStop(responses, estimate, pool)) {
                CatItem next = engine.nextItem(pool, responses, estimate).orElseThrow();
                boolean correct = world.nextDouble() < CatEngine.probability(next, trueTheta);
                responses.add(answer(next, correct));
                estimate = engine.estimate(responses, 3.0);
            }
            Set<Long> ids = new HashSet<>();
            responses.forEach(r -> ids.add(r.item().id()));
            assertThat(ids).as("no question repeats").hasSize(responses.size());
            assertThat(responses.size()).isBetween(15, 30);
            for (String skill : skills) {
                assertThat(responses.stream().filter(r -> r.item().skill().equals(skill)).count())
                        .as(skill + " reaches its minimum").isGreaterThanOrEqualTo(2);
            }
            totalError += Math.abs(estimate.theta() - trueTheta);
            totalQuestions += responses.size();
        }
        assertThat(totalError / learners).as("mean absolute error in levels").isLessThan(0.55);
        assertThat((double) totalQuestions / learners).as("mean test length").isLessThan(22);
    }
}
