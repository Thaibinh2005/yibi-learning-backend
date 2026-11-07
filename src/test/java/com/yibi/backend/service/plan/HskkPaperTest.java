package com.yibi.backend.service.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class HskkPaperTest {

    private static HskkPaper.Material material() {
        List<String> lines = new ArrayList<>(IntStream.range(0, 40).mapToObj(i -> "你好我们今天学习汉语" + i).toList());
        List<String> passages = new ArrayList<>(IntStream.range(0, 8).mapToObj(i -> "今天天气很好我们一起去公园玩".repeat(8) + i).toList());
        return new HskkPaper.Material(lines, passages);
    }

    private static List<Integer> counts(String tier) {
        return HskkPaper.build(tier, material(), 7).stream().map(part -> part.questions().size()).toList();
    }

    private static HskkPaper.Question question(String kind, String reference, int expected) {
        return new HskkPaper.Question("x", kind, null, null, reference, expected, 0, 10);
    }

    @Test
    void papersFollowTheOfficialStructure() {
        assertThat(counts(HskkPaper.PRIMARY)).containsExactly(15, 10, 2);
        assertThat(counts(HskkPaper.INTERMEDIATE)).containsExactly(10, 2, 2);
        assertThat(counts(HskkPaper.ADVANCED)).containsExactly(3, 1, 2);
    }

    @Test
    void weightsAddUpToOneHundred() {
        for (String tier : List.of(HskkPaper.PRIMARY, HskkPaper.INTERMEDIATE, HskkPaper.ADVANCED)) {
            assertThat(HskkPaper.build(tier, material(), 1).stream().mapToInt(HskkPaper.Part::weight).sum()).as(tier).isEqualTo(100);
        }
    }

    @Test
    void theSameSeedRebuildsTheSamePaper() {
        assertThat(HskkPaper.build(HskkPaper.ADVANCED, material(), 42)).isEqualTo(HskkPaper.build(HskkPaper.ADVANCED, material(), 42));
        assertThat(HskkPaper.build(HskkPaper.PRIMARY, material(), 42)).isNotEqualTo(HskkPaper.build(HskkPaper.PRIMARY, material(), 43));
    }

    @Test
    void listeningQuestionsHideTheirTextAndReadingOnesShowIt() {
        List<HskkPaper.Part> parts = HskkPaper.build(HskkPaper.ADVANCED, material(), 5);
        HskkPaper.Question retell = parts.get(0).questions().get(0);
        HskkPaper.Question read = parts.get(1).questions().get(0);
        assertThat(retell.listenText()).isNotNull();
        assertThat(retell.showText()).isNull();
        assertThat(read.showText()).isNotNull();
        assertThat(read.listenText()).isNull();
    }

    @Test
    void contentNeverComesFromAboveTheLearner() {
        assertThat(HskkPaper.contentLevel(HskkPaper.PRIMARY, 1)).isEqualTo(1);
        assertThat(HskkPaper.contentLevel(HskkPaper.PRIMARY, 5)).isEqualTo(2);
        assertThat(HskkPaper.contentLevel(HskkPaper.INTERMEDIATE, 3)).isEqualTo(3);
        assertThat(HskkPaper.contentLevel(HskkPaper.ADVANCED, 7)).isEqualTo(7);
    }

    @Test
    void silenceScoresZeroWhateverTheKind() {
        for (String kind : List.of(HskkPaper.REPEAT, HskkPaper.REPLY, HskkPaper.SPEAK, HskkPaper.RETELL, HskkPaper.READ)) {
            assertThat(HskkPaper.score(question(kind, "你好吗", 10), 0.9, "")).as(kind).isZero();
            assertThat(HskkPaper.score(question(kind, "你好吗", 10), 0.9, "ok")).as(kind).isZero();
        }
    }

    @Test
    void repeatingUsesTheAccuracy() {
        assertThat(HskkPaper.score(question(HskkPaper.REPEAT, "你好吗", 0), 0.75, "你好")).isEqualTo(75);
        assertThat(HskkPaper.score(question(HskkPaper.READ, "你好吗", 0), 1.0, "你好吗")).isEqualTo(100);
    }

    @Test
    void freeSpeechIsJudgedOnHowMuchWasSaid() {
        assertThat(HskkPaper.score(question(HskkPaper.SPEAK, "问题", 40), 0, "我".repeat(20))).isEqualTo(50);
        assertThat(HskkPaper.score(question(HskkPaper.SPEAK, "问题", 40), 0, "我".repeat(100))).isEqualTo(100);
    }

    @Test
    void retellingCountsTheWordingThatCameBack() {
        String reference = "一二三四五六七八九十";
        assertThat(HskkPaper.score(question(HskkPaper.RETELL, reference, 0), 0, "一二三")).isEqualTo(50);
        assertThat(HskkPaper.score(question(HskkPaper.RETELL, reference, 0), 0, "一二三四五六")).isEqualTo(100);
        assertThat(HskkPaper.score(question(HskkPaper.RETELL, reference, 0), 0, "天地人")).isZero();
    }

    @Test
    void retellingInTraditionalCharactersStillCounts() {
        String reference = "我们学习汉语很有意思";
        assertThat(HskkPaper.score(question(HskkPaper.RETELL, reference, 0), 0, "我們學習漢語很有意思")).isEqualTo(100);
    }

    @Test
    void theTotalAveragesEachPartByItsWeight() {
        List<HskkPaper.Part> parts = HskkPaper.build(HskkPaper.INTERMEDIATE, material(), 3);
        Map<String, Integer> scores = new HashMap<>();
        parts.get(0).questions().forEach(q -> scores.put(q.id(), 100));
        assertThat(HskkPaper.total(parts, scores)).isEqualTo(40);
        scores.put(parts.get(1).questions().get(0).id(), 100);
        assertThat(HskkPaper.total(parts, scores)).isEqualTo(55);
        assertThat(HskkPaper.total(parts, Map.of())).isZero();
    }

    @Test
    void targetScoreRaisesTheBarWithinLimits() {
        assertThat(ScoreGoal.passPercent("HSK", 4, null)).isEqualTo(80);
        assertThat(ScoreGoal.passPercent("HSK", 4, 180)).isEqualTo(80);
        assertThat(ScoreGoal.passPercent("HSK", 4, 270)).isEqualTo(90);
        assertThat(ScoreGoal.passPercent("HSK", 4, 300)).isEqualTo(95);
        assertThat(ScoreGoal.passPercent("HSK", 2, 180)).isEqualTo(90);
        assertThat(ScoreGoal.passPercent("HSKK", 2, 85)).isEqualTo(85);
        assertThat(ScoreGoal.passPercent("COMMUNICATION", 2, 300)).isEqualTo(80);
    }

    @Test
    void scalesFollowTheTest() {
        assertThat(ScoreGoal.scale("HSK", 2)).isEqualTo(200);
        assertThat(ScoreGoal.scale("HSK", 3)).isEqualTo(300);
        assertThat(ScoreGoal.scale("HSKK", 6)).isEqualTo(100);
        assertThat(ScoreGoal.scale("COMMUNICATION", 6)).isZero();
        assertThat(ScoreGoal.estimatedScore(70, 300)).isEqualTo(210);
    }
}
