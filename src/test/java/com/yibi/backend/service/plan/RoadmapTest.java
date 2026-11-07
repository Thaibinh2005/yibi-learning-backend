package com.yibi.backend.service.plan;

import com.yibi.backend.service.plan.Roadmap.Node;
import com.yibi.backend.service.plan.Roadmap.NodeRef;
import com.yibi.backend.service.plan.Roadmap.Unit;
import com.yibi.backend.service.plan.Roadmap.UnitRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoadmapTest {

    private static final List<UnitRef> UNITS = List.of(
            new UnitRef(1, 1, "Chào hỏi", "问候", "👋", List.of(
                    new NodeRef(11, "Bài 1", "LESSON"),
                    new NodeRef(12, "Bài 2", "LESSON"),
                    new NodeRef(13, "Hội thoại", "DIALOGUE"),
                    new NodeRef(14, "Kiểm tra", "CHECKPOINT"))),
            new UnitRef(2, 2, "Gia đình", "家庭", "👪", List.of(
                    new NodeRef(21, "Bài 1", "LESSON"),
                    new NodeRef(22, "Kiểm tra", "CHECKPOINT"))));

    private static List<Unit> layOut(int level, int learner, Set<Long> done) {
        return Roadmap.layOut(UNITS, level, learner, done, Map.of(), Map.of());
    }

    private static List<String> statuses(Unit unit) {
        return unit.nodes().stream().map(Node::status).toList();
    }

    @Test
    void onTheLearnersLevelOnlyTheNextNodeIsOpen() {
        List<Unit> units = layOut(1, 1, Set.of());
        assertEquals(List.of("CURRENT", "LOCKED", "LOCKED", "LOCKED"), statuses(units.get(0)));
        assertEquals(List.of("LOCKED", "LOCKED"), statuses(units.get(1)));
        assertEquals("CURRENT", units.get(0).status());
        assertEquals("LOCKED", units.get(1).status());
    }

    @Test
    void passingANodeOpensTheNextOne() {
        List<Unit> units = layOut(1, 1, Set.of(11L));
        assertEquals(List.of("DONE", "CURRENT", "LOCKED", "LOCKED"), statuses(units.get(0)));
        assertEquals(1, units.get(0).completed());
    }

    @Test
    void finishingAUnitMovesToTheNextUnit() {
        List<Unit> units = layOut(1, 1, Set.of(11L, 12L, 13L, 14L));
        assertEquals("DONE", units.get(0).status());
        assertEquals(List.of("CURRENT", "LOCKED"), statuses(units.get(1)));
    }

    @Test
    void levelsAboveTheLearnerAreCompletelyLocked() {
        List<Unit> units = layOut(2, 1, Set.of());
        assertTrue(units.stream().flatMap(unit -> unit.nodes().stream()).allMatch(node -> "LOCKED".equals(node.status())));
    }

    @Test
    void levelsBelowTheLearnerAreOpenForReview() {
        List<Unit> units = layOut(1, 3, Set.of(11L));
        assertEquals(List.of("DONE", "OPEN", "OPEN", "OPEN"), statuses(units.get(0)));
        assertEquals("OPEN", units.get(1).status());
    }

    @Test
    void nodesKeepTheirOrderForEveryLearner() {
        assertEquals(List.of(11L, 12L, 13L, 14L), layOut(1, 1, Set.of()).get(0).nodes().stream().map(Node::id).toList());
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
        Roadmap.Result result = new Roadmap.Result(1, 1, List.of(), layOut(1, 1, Set.of(11L)));
        assertEquals(12L, result.pathOrder().get(0).id());
        assertEquals(5, result.pathOrder().size());
    }
}
