package com.yibi.backend.service.mocktest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.yibi.backend.exception.BadRequestException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Rules about a sample paper's JSON (made by yibi-speech-service/app/mocktest): which answers count, what a timed
 * exam may see, and what must be settled before an admin can publish it.
 *
 * <p>A question is a choice (answer "A"–"F", options of its own, of its group or of its section), {@code "fill"}
 * (write the character for the pinyin; answer is that text) or {@code "write"} (open writing with a reference answer;
 * kept but not scored).
 */
public final class MockTestPapers {

    public static final Set<String> SKILLS = Set.of("listening", "reading", "writing");
    static final int MAX_FILL = 8;
    static final int MAX_WRITING = 3000;

    private MockTestPapers() {
    }

    /** What the paper expects for one question. */
    public record Item(String skill, String type, String answer) {
        public boolean graded() {
            return !"write".equals(type);
        }
    }

    /** Question number → its skill, type and correct answer, in paper order. */
    public static Map<Integer, Item> answerKey(JsonNode paper) {
        Map<Integer, Item> key = new LinkedHashMap<>();
        for (JsonNode section : paper.path("sections")) {
            for (JsonNode q : section.path("questions")) {
                key.put(q.path("no").asInt(), new Item(section.path("skill").asText(), q.path("type").asText("choice"),
                        q.hasNonNull("answer") ? q.path("answer").asText() : null));
            }
        }
        return key;
    }

    /** The learner's answer as it is stored, or null when it is not a possible answer to this question. */
    public static String keepAnswer(Item item, String value) {
        if (value == null) return null;
        return switch (item.type()) {
            case "fill" -> {
                String text = value.replaceAll("\\s+", "");
                yield text.isEmpty() || text.length() > MAX_FILL ? null : text;
            }
            case "write" -> {
                String text = value.strip();
                yield text.isEmpty() ? null : text.length() > MAX_WRITING ? text.substring(0, MAX_WRITING) : text;
            }
            default -> value.matches("[A-F]") ? value : null;
        };
    }

    public static boolean correct(Item item, String value) {
        if (!item.graded() || item.answer() == null || value == null) return false;
        if ("fill".equals(item.type())) return bare(value).equals(bare(item.answer()));
        return item.answer().equals(value);
    }

    /** A written character without spaces or punctuation around it ("的。" counts as "的"). */
    static String bare(String text) {
        return text.replaceAll("[\\s\\p{Punct}，。、！？；：（）“”‘’《》]", "");
    }

    /** A timed exam must not see answers, transcripts or model answers before it is handed in; examples keep theirs. */
    public static JsonNode visiblePaper(JsonNode paper, String mode, boolean finished) {
        if (finished || "PRACTICE".equals(mode)) return paper;
        JsonNode copy = paper.deepCopy();
        for (JsonNode section : copy.path("sections")) {
            for (JsonNode q : section.path("questions")) {
                ((ObjectNode) q).remove(List.of("answer", "transcript", "reference"));
            }
            for (JsonNode g : section.path("groups")) {
                ((ObjectNode) g).remove("transcript");
            }
        }
        return copy;
    }

    // ── Checking a draft ──────────────────────────────────────────────────────

    /** Things that block publishing: clips still to be checked by ear, missing or impossible answers. */
    public static List<String> reviewProblems(JsonNode paper) {
        List<String> out = new ArrayList<>();
        int questions = 0;
        for (JsonNode section : paper.path("sections")) {
            Map<String, JsonNode> groups = new LinkedHashMap<>();
            for (JsonNode g : section.path("groups")) {
                groups.put(g.path("id").asText(), g);
                if (g.path("audio").path("needsReview").asBoolean(false)) {
                    out.add("Câu %s: cần nghe lại đoạn audio nhóm".formatted(range(g)));
                }
            }
            for (JsonNode q : section.path("questions")) {
                questions++;
                int no = q.path("no").asInt();
                if (q.path("audio").path("needsReview").asBoolean(false)) out.add("Câu %d: cần nghe lại đoạn audio".formatted(no));
                String type = q.path("type").asText("choice");
                if ("write".equals(type)) continue;
                String answer = q.hasNonNull("answer") ? q.path("answer").asText() : "";
                if (answer.isBlank()) {
                    out.add("Câu %d: thiếu đáp án".formatted(no));
                } else if ("choice".equals(type)) {
                    Set<String> keys = optionKeys(section, groups, q);
                    if (!keys.isEmpty() && !keys.contains(answer)) {
                        out.add("Câu %d: đáp án %s không có trong các lựa chọn".formatted(no, answer));
                    }
                }
            }
        }
        if (questions == 0) out.add("Đề chưa có câu nào");
        return out;
    }

    private static String range(JsonNode group) {
        return group.path("range").path(0).asInt() + "–" + group.path("range").path(1).asInt();
    }

    private static Set<String> optionKeys(JsonNode section, Map<String, JsonNode> groups, JsonNode q) {
        JsonNode options = q.has("options") ? q.path("options")
                : "group".equals(q.path("optionsFrom").asText()) ? groups.getOrDefault(q.path("group").asText(), q).path("sharedOptions")
                : section.path("sharedOptions");
        Set<String> keys = new HashSet<>();
        options.forEach(o -> keys.add(o.path("key").asText()));
        return keys;
    }

    /** Structure an edited draft must keep: known skills, numbered questions without duplicates, known types. */
    public static void validateStructure(JsonNode paper) {
        if (!paper.path("sections").isArray() || paper.path("sections").isEmpty()) {
            throw new BadRequestException("Đề phải có ít nhất một phần.");
        }
        Set<Integer> seen = new HashSet<>();
        for (JsonNode section : paper.path("sections")) {
            if (!SKILLS.contains(section.path("skill").asText())) throw new BadRequestException("Kỹ năng không hợp lệ: " + section.path("skill"));
            if (!section.path("questions").isArray()) throw new BadRequestException("Mỗi phần phải có danh sách câu hỏi.");
            for (JsonNode q : section.path("questions")) {
                int no = q.path("no").asInt(0);
                if (no < 1 || no > 300 || !seen.add(no)) throw new BadRequestException("Số câu không hợp lệ hoặc bị trùng: " + q.path("no"));
                String type = q.path("type").asText("choice");
                if (!Set.of("choice", "fill", "write").contains(type)) throw new BadRequestException("Câu %d: dạng câu không hợp lệ".formatted(no));
                String answer = q.path("answer").asText("");
                if ("choice".equals(type) && !answer.isEmpty() && !answer.matches("[A-F]")) {
                    throw new BadRequestException("Câu %d: đáp án phải là một chữ A–F".formatted(no));
                }
                if ("fill".equals(type) && answer.length() > MAX_FILL) throw new BadRequestException("Câu %d: đáp án quá dài".formatted(no));
            }
        }
    }

    /** The paper learners get: review flags and extraction notes removed. */
    public static JsonNode forLearners(JsonNode draft) {
        ObjectNode copy = draft.deepCopy();
        copy.remove(List.of("problems", "cover", "audioProblems"));
        strip(copy);
        return copy;
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove("needsReview");
            object.forEach(MockTestPapers::strip);
        } else if (node instanceof ArrayNode array) {
            array.forEach(MockTestPapers::strip);
        }
    }

    // ── Clips and media ───────────────────────────────────────────────────────

    /**
     * Every clip of the paper by key — "L1_intro", "q05", "q29-30" — as the live audio node, so changing it changes
     * the paper.
     */
    public static Map<String, ObjectNode> clips(JsonNode paper) {
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        for (JsonNode section : paper.path("sections")) {
            if (section.path("introAudio").isObject()) out.put("L" + section.path("part").asInt() + "_intro", (ObjectNode) section.path("introAudio"));
            for (JsonNode g : section.path("groups")) {
                if (g.path("audio").isObject()) {
                    out.put("q%02d-%02d".formatted(g.path("range").path(0).asInt(), g.path("range").path(1).asInt()), (ObjectNode) g.path("audio"));
                }
            }
            for (JsonNode q : section.path("questions")) {
                if (q.path("audio").isObject()) out.putIfAbsent("q%02d".formatted(q.path("no").asInt()), (ObjectNode) q.path("audio"));
            }
        }
        return out;
    }

    /** Rewrites every picture and clip path ("img/…", "audio/…") in place, e.g. to the stored file's URL. */
    public static void rewriteMedia(JsonNode node, UnaryOperator<String> path) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode value = object.get(name);
                if (("image".equals(name) || "file".equals(name)) && value.isTextual()) {
                    object.put(name, path.apply(value.asText()));
                } else if ("images".equals(name) && value.isArray()) {
                    ArrayNode rewritten = object.putArray(name);
                    value.forEach(v -> rewritten.add(new TextNode(path.apply(v.asText()))));
                } else {
                    rewriteMedia(value, path);
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(v -> rewriteMedia(v, path));
        }
    }

    /** Applies a text change to every transcript (Whisper sometimes writes traditional characters). */
    public static void rewriteTranscripts(JsonNode node, UnaryOperator<String> change) {
        if (node instanceof ObjectNode object) {
            if (object.path("transcript").isTextual()) object.put("transcript", change.apply(object.path("transcript").asText()));
            object.forEach(v -> rewriteTranscripts(v, change));
        } else if (node instanceof ArrayNode array) {
            array.forEach(v -> rewriteTranscripts(v, change));
        }
    }
}
