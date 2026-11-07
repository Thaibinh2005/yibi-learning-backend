package com.yibi.backend.service.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides what a learner may open on one level of the path. Levels below theirs are open for review; on their
 * level they move one node at a time; levels above stay locked until they finish or pass the level test.
 * It has no database access so the rules can be tested directly.
 */
public final class Roadmap {

    public static final String DONE = "DONE";
    /** The one node to do next on the learner's level. */
    public static final String CURRENT = "CURRENT";
    /** Not done, but open because the level is below the learner's. */
    public static final String OPEN = "OPEN";
    public static final String LOCKED = "LOCKED";
    public static final String CHECKPOINT = "CHECKPOINT";

    private Roadmap() { }

    public record NodeRef(long id, String title, String type) { }

    public record UnitRef(long id, int number, String title, String titleZh, String icon, List<NodeRef> nodes) { }

    public record Node(long id, String title, String type, int minutes, String status, int wrongAnswers, int bestScore) { }

    public record Unit(long id, int number, String title, String titleZh, String icon, String status,
                       int completed, int total, List<Node> nodes) { }

    public record LevelSummary(int level, String label, String status, int completed, int total) { }

    /** @param levels every level of the path; units holds only the level asked for */
    public record Result(int learnerLevel, int shownLevel, List<LevelSummary> levels, List<Unit> units) {
        /** Unfinished nodes of the shown level in the order the learner should meet them. */
        public List<Node> pathOrder() {
            List<Node> open = new ArrayList<>();
            units.forEach(unit -> unit.nodes().stream().filter(node -> !DONE.equals(node.status())).forEach(open::add));
            return open;
        }
    }

    /**
     * @param units     the units of the level being shown, in order
     * @param scores    best score per node the learner has attempted
     * @param completed nodes the learner has passed
     * @param wrong     wrong answers per node
     */
    public static List<Unit> layOut(List<UnitRef> units, int level, int learnerLevel, Set<Long> completed,
                                    Map<Long, Integer> scores, Map<Long, Integer> wrong) {
        boolean currentAssigned = false;
        List<Unit> result = new ArrayList<>();
        for (UnitRef unit : units) {
            List<Node> nodes = new ArrayList<>();
            int done = 0;
            boolean hasCurrent = false;
            for (NodeRef node : unit.nodes()) {
                String status;
                if (completed.contains(node.id())) {
                    status = DONE;
                    done++;
                } else if (level > learnerLevel) {
                    status = LOCKED;
                } else if (level < learnerLevel) {
                    status = OPEN;
                } else if (!currentAssigned) {
                    status = CURRENT;
                    currentAssigned = true;
                    hasCurrent = true;
                } else {
                    status = LOCKED;
                }
                nodes.add(new Node(node.id(), node.title(), node.type(), DailyPlanner.lessonMinutes(node.type()), status,
                        wrong.getOrDefault(node.id(), 0), scores.getOrDefault(node.id(), 0)));
            }
            String unitStatus = done == nodes.size() ? DONE : hasCurrent ? CURRENT : level < learnerLevel ? OPEN : LOCKED;
            result.add(new Unit(unit.id(), unit.number(), unit.title(), unit.titleZh(), unit.icon(), unitStatus, done, nodes.size(), nodes));
        }
        return result;
    }

    public static String levelStatus(int level, int learnerLevel, int completed, int total) {
        if (total > 0 && completed == total) return DONE;
        if (level == learnerLevel) return CURRENT;
        return level < learnerLevel ? OPEN : LOCKED;
    }

    public static String levelLabel(int level) {
        return level >= 7 ? "HSK 7–9" : "HSK " + level;
    }
}
