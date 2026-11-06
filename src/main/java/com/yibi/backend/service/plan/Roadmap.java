package com.yibi.backend.service.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

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

    /** How strongly a weak skill pulls its nodes forward in the unit in progress. */
    private static final double WEAK_SKILL_WEIGHT = 3;

    /** The types that matter most for a goal come first; it makes them likelier to come early, not certain. */
    private static final Map<String, List<String>> TYPE_ORDER_BY_GOAL = Map.of(
            "HSK", List.of("VOCABULARY", "HANZI", "GRAMMAR", "READING", "LISTENING", "DIALOGUE"),
            "HSKK", List.of("VOCABULARY", "LISTENING", "DIALOGUE", "READING", "GRAMMAR", "HANZI"),
            "COMMUNICATION", List.of("VOCABULARY", "DIALOGUE", "LISTENING", "GRAMMAR", "READING", "HANZI"));

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
     * @param goalType  HSK, HSKK or COMMUNICATION; favours some node types when ordering a unit
     * @param weakTypes node types of weak skills; favoured in the unit the learner is on
     * @param learnerSeed makes the order inside a unit differ between learners but stay put for each of them
     */
    public static List<Unit> layOut(List<UnitRef> units, int level, int learnerLevel, Set<Long> completed,
                                    Map<Long, Integer> scores, Map<Long, Integer> wrong, String goalType,
                                    Collection<String> weakTypes, long learnerSeed) {
        List<String> goalOrder = TYPE_ORDER_BY_GOAL.getOrDefault(goalType, TYPE_ORDER_BY_GOAL.get("HSK"));
        boolean currentAssigned = false;
        List<Unit> result = new ArrayList<>();
        for (UnitRef unit : units) {
            boolean unitOpen = unit.nodes().stream().anyMatch(node -> !completed.contains(node.id()));
            // Only the unit in progress adapts to weak skills, so later units keep a stable order.
            Collection<String> weak = unitOpen && !currentAssigned ? weakTypes : List.of();
            List<Node> nodes = new ArrayList<>();
            int done = 0;
            boolean hasCurrent = false;
            for (NodeRef node : orderNodes(unit.nodes(), completed, scores.keySet(), goalOrder, weak, learnerSeed)) {
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

    /**
     * What is done comes first, then what the learner has started, so a failed node stays open for another try
     * however the rest moves. The first vocabulary node opens a unit since it teaches the words the others use, and
     * the unit test closes it. Everything between is drawn at random, favouring the goal and weak skills.
     */
    private static List<NodeRef> orderNodes(List<NodeRef> nodes, Set<Long> completed, Set<Long> started,
                                            List<String> goalOrder, Collection<String> weakTypes, long learnerSeed) {
        NodeRef opener = nodes.stream().filter(node -> "VOCABULARY".equals(node.type())).findFirst().orElse(null);
        List<NodeRef> done = new ArrayList<>();
        List<NodeRef> inProgress = new ArrayList<>();
        List<NodeRef> drawn = new ArrayList<>();
        List<NodeRef> tests = new ArrayList<>();
        for (NodeRef node : nodes) {
            if (completed.contains(node.id())) done.add(node);
            else if (CHECKPOINT.equals(node.type())) tests.add(node);
            else if (started.contains(node.id())) inProgress.add(node);
            else if (node != opener) drawn.add(node);
        }
        Comparator<NodeRef> byDraw = Comparator.comparingDouble(node -> -draw(node, goalOrder, weakTypes, learnerSeed));
        drawn.sort(byDraw);
        List<NodeRef> ordered = new ArrayList<>(done);
        ordered.addAll(inProgress);
        if (opener != null && !done.contains(opener) && !inProgress.contains(opener)) ordered.add(opener);
        ordered.addAll(drawn);
        ordered.addAll(tests);
        return ordered;
    }

    /** A weighted random draw (Efraimidis-Spirakis): the higher the weight, the likelier a high result. */
    private static double draw(NodeRef node, List<String> goalOrder, Collection<String> weakTypes, long learnerSeed) {
        int rank = goalOrder.indexOf(node.type());
        double weight = rank < 0 ? 1 : 1 + (goalOrder.size() - rank) * 0.5;
        if (weakTypes.contains(node.type())) weight *= WEAK_SKILL_WEIGHT;
        double uniform = new SplittableRandom(learnerSeed * 31 + node.id() * 1_000_003L).nextDouble();
        return Math.pow(uniform, 1 / weight);
    }
}
