package com.yibi.backend.service.plan;

import com.yibi.backend.service.plan.Roadmap.Node;
import com.yibi.backend.service.plan.Roadmap.NodeRef;
import com.yibi.backend.service.plan.Roadmap.Unit;
import com.yibi.backend.service.plan.Roadmap.UnitRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoadmapTest {

    private static final List<UnitRef> UNITS = List.of(
            new UnitRef(1, 1, "Chào hỏi", "问候", "👋", List.of(
                    new NodeRef(11, "Từ vựng", "VOCABULARY"),
                    new NodeRef(12, "Ngữ pháp", "GRAMMAR"),
                    new NodeRef(13, "Hội thoại", "DIALOGUE"),
                    new NodeRef(14, "Kiểm tra", "CHECKPOINT"))),
            new UnitRef(2, 2, "Gia đình", "家庭", "👪", List.of(
                    new NodeRef(21, "Từ vựng", "VOCABULARY"),
                    new NodeRef(22, "Kiểm tra", "CHECKPOINT"))));

    private static List<Unit> layOut(int level, int learner, Set<Long> done, String goal, List<String> weak) {
        return Roadmap.layOut(UNITS, level, learner, done, Map.of(), Map.of(), goal, weak, 7);
    }

    private static List<String> statuses(Unit unit) {
        return unit.nodes().stream().map(Node::status).toList();
    }

    private static List<String> types(Unit unit) {
        return unit.nodes().stream().map(Node::type).toList();
    }

    @Test
    void onTheLearnersLevelOnlyTheNextNodeIsOpen() {
        List<Unit> units = layOut(1, 1, Set.of(), "HSK", List.of());
        assertEquals(List.of("CURRENT", "LOCKED", "LOCKED", "LOCKED"), statuses(units.get(0)));
        assertEquals(List.of("LOCKED", "LOCKED"), statuses(units.get(1)));
        assertEquals("CURRENT", units.get(0).status());
        assertEquals("LOCKED", units.get(1).status());
    }

    @Test
    void passingANodeOpensTheNextOne() {
        List<Unit> units = layOut(1, 1, Set.of(11L), "HSK", List.of());
        assertEquals(List.of("DONE", "CURRENT", "LOCKED", "LOCKED"), statuses(units.get(0)));
        assertEquals(1, units.get(0).completed());
    }

    @Test
    void finishingAUnitMovesToTheNextUnit() {
        List<Unit> units = layOut(1, 1, Set.of(11L, 12L, 13L, 14L), "HSK", List.of());
        assertEquals("DONE", units.get(0).status());
        assertEquals(List.of("CURRENT", "LOCKED"), statuses(units.get(1)));
    }

    @Test
    void levelsAboveTheLearnerAreCompletelyLocked() {
        List<Unit> units = layOut(2, 1, Set.of(), "HSK", List.of());
        assertTrue(units.stream().flatMap(unit -> unit.nodes().stream()).allMatch(node -> "LOCKED".equals(node.status())));
    }

    @Test
    void levelsBelowTheLearnerAreOpenForReview() {
        List<Unit> units = layOut(1, 3, Set.of(11L), "HSK", List.of());
        assertEquals(List.of("DONE", "OPEN", "OPEN", "OPEN"), statuses(units.get(0)));
        assertEquals("OPEN", units.get(1).status());
    }

    private static final List<UnitRef> FULL_UNIT = List.of(new UnitRef(1, 1, "Chào hỏi", "问候", "👋", List.of(
            new NodeRef(11, "Từ vựng 1", "VOCABULARY"),
            new NodeRef(12, "Từ vựng 2", "VOCABULARY"),
            new NodeRef(13, "Chữ Hán", "HANZI"),
            new NodeRef(14, "Ngữ pháp", "GRAMMAR"),
            new NodeRef(15, "Hội thoại", "DIALOGUE"),
            new NodeRef(16, "Đọc", "READING"),
            new NodeRef(17, "Nghe", "LISTENING"),
            new NodeRef(18, "Kiểm tra", "CHECKPOINT"))));

    private static List<Long> order(long seed, String goal, List<String> weak, Set<Long> done, Map<Long, Integer> scores) {
        return Roadmap.layOut(FULL_UNIT, 1, 1, done, scores, Map.of(), goal, weak, seed).get(0).nodes().stream().map(Node::id).toList();
    }

    @Test
    void learnersGetDifferentOrdersButEachKeepsTheirs() {
        Set<List<Long>> orders = new HashSet<>();
        for (long seed = 0; seed < 50; seed++) orders.add(order(seed, "HSK", List.of(), Set.of(), Map.of()));
        assertTrue(orders.size() > 20, "orders: " + orders.size());
        assertEquals(order(5, "HSK", List.of(), Set.of(), Map.of()), order(5, "HSK", List.of(), Set.of(), Map.of()));
    }

    @Test
    void aUnitAlwaysOpensWithItsFirstWordsAndClosesWithItsTest() {
        for (long seed = 0; seed < 200; seed++) {
            List<Long> order = order(seed, "COMMUNICATION", List.of("GRAMMAR"), Set.of(), Map.of());
            assertEquals(11L, order.get(0));
            assertEquals(18L, order.get(order.size() - 1));
            assertEquals(8, new HashSet<>(order).size());
        }
    }

    private static double averagePosition(long node, String goal, List<String> weak) {
        double sum = 0;
        for (long seed = 0; seed < 500; seed++) sum += order(seed, goal, weak, Set.of(), Map.of()).indexOf(node);
        return sum / 500;
    }

    @Test
    void theGoalMakesItsSkillsComeEarlierOnAverage() {
        assertTrue(averagePosition(15, "COMMUNICATION", List.of()) < averagePosition(15, "HSK", List.of()), "dialogue");
        assertTrue(averagePosition(13, "HSK", List.of()) < averagePosition(13, "COMMUNICATION", List.of()), "characters");
    }

    @Test
    void weakSkillsComeEarlierOnAverageOnlyInTheUnitInProgress() {
        assertTrue(averagePosition(14, "HSK", List.of("GRAMMAR")) < averagePosition(14, "HSK", List.of()));
        List<Unit> started = layOut(1, 1, Set.of(), "HSK", List.of("DIALOGUE"));
        List<Unit> plain = layOut(1, 1, Set.of(), "HSK", List.of());
        assertEquals(types(plain.get(1)), types(started.get(1)));
    }

    @Test
    void aFailedNodeStaysOpenHoweverTheWeakSkillsChange() {
        Map<Long, Integer> failed = Map.of(16L, 25);
        for (long seed = 0; seed < 100; seed++) {
            List<Node> nodes = Roadmap.layOut(FULL_UNIT, 1, 1, Set.of(11L), failed, Map.of(), "HSK", List.of("GRAMMAR", "HANZI"), seed).get(0).nodes();
            assertEquals(List.of(11L, 16L), nodes.stream().limit(2).map(Node::id).toList());
            assertEquals("CURRENT", nodes.get(1).status());
        }
    }

    @Test
    void whatIsDoneComesFirstSoTheNextNodeIsNeverBehindIt() {
        for (long seed = 0; seed < 100; seed++) {
            List<Node> nodes = Roadmap.layOut(FULL_UNIT, 1, 1, Set.of(11L, 17L), Map.of(), Map.of(), "HSK", List.of(), seed).get(0).nodes();
            List<String> statuses = new ArrayList<>(nodes.stream().map(Node::status).toList());
            assertEquals(List.of("DONE", "DONE", "CURRENT"), statuses.subList(0, 3));
        }
    }

    @Test
    void levelStatusReflectsTheLearnersPosition() {
        assertEquals("DONE", Roadmap.levelStatus(1, 2, 5, 5));
        assertEquals("OPEN", Roadmap.levelStatus(1, 2, 1, 5));
        assertEquals("CURRENT", Roadmap.levelStatus(2, 2, 0, 5));
        assertEquals("LOCKED", Roadmap.levelStatus(3, 2, 0, 5));
    }

    @Test
    void pathOrderSkipsWhatIsDone() {
        Roadmap.Result result = new Roadmap.Result(1, 1, List.of(), layOut(1, 1, Set.of(11L), "HSK", List.of()));
        assertEquals("CURRENT", result.pathOrder().get(0).status());
        assertNotEquals(11L, result.pathOrder().get(0).id());
        assertEquals(5, result.pathOrder().size());
    }
}
