package com.yibi.backend.service.plan;

import com.yibi.backend.service.plan.PathQuiz.Question;
import com.yibi.backend.service.plan.PathQuiz.Word;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Lays out one lesson: new words, characters, grammar and speaking woven together, always teaching before asking.
 * The same content and seed give the same lesson, so answers are graded by laying it out again and nothing is stored.
 * No database access or AI call.
 */
public final class LessonPlan {

    public static final String TEACH_WORD = "TEACH_WORD";
    public static final String TEACH_GRAMMAR = "TEACH_GRAMMAR";
    public static final String QUESTION = "QUESTION";
    public static final String SPEAK = "SPEAK";

    private static final int MORE_SPEAKING = 1;
    private static final int MORE_CHARACTERS = 2;
    private static final int MORE_PRONUNCIATION = 4;
    private static final int FLAG_BITS = 3;

    private LessonPlan() { }

    /** @param data the word, grammar point or dialogue line of a teaching or speaking step */
    public record Step(String kind, String questionId, Integer lineIndex, Map<String, Object> data) { }

    public record Plan(List<Step> steps, List<Question> questions) { }

    /** A group of steps that has to stay together and in order. */
    private static final class Block {
        final List<Step> steps = new ArrayList<>();
        final List<Question> questions = new ArrayList<>();

        Block ask(Question question) {
            if (question != null) {
                steps.add(new Step(QUESTION, null, null, null));
                questions.add(question);
            }
            return this;
        }

        Block show(Step step) {
            steps.add(step);
            return this;
        }
    }

    /**
     * Puts what the lesson should stress into the seed. A learner's weak skills can change during a lesson, and
     * grading has to lay out the same lesson as they were shown.
     */
    public static long seed(String goalType, Collection<String> weakSkills, int random) {
        int flags = 0;
        if ("HSKK".equals(goalType) || "COMMUNICATION".equals(goalType) || weakSkills.contains("speaking")) flags |= MORE_SPEAKING;
        if ("HSK".equals(goalType) || weakSkills.contains("characters")) flags |= MORE_CHARACTERS;
        if (weakSkills.contains("phonetics")) flags |= MORE_PRONUNCIATION;
        return ((long) (random >>> FLAG_BITS) << FLAG_BITS) | flags;
    }

    /** A lesson at HSK 1; its depth follows the seed (see {@link KnowledgeDepth#forSeed}). */
    public static Plan build(Map<String, Object> content, List<Word> pool, List<String> characterPool, long seed) {
        return build(content, pool, characterPool, seed, 1);
    }

    /**
     * A lesson whose word cards and questions follow the depth of its HSK level, when the seed is marked with
     * {@link KnowledgeDepth#LEVEL_RULES}; an older seed rebuilds the lesson exactly as it was first shown.
     */
    public static Plan build(Map<String, Object> content, List<Word> pool, List<String> characterPool, long seed, int hskLevel) {
        KnowledgeDepth depth = KnowledgeDepth.forSeed(hskLevel, seed);
        int flags = (int) (seed & ((1 << FLAG_BITS) - 1));
        Random random = new Random(seed);

        List<Map<String, Object>> taught = new ArrayList<>(list(content.get("words")));
        Collections.shuffle(taught, random);
        List<Block> blocks = new ArrayList<>();
        List<Block> wordBlocks = new ArrayList<>();
        for (int i = 0; i < taught.size(); i++) {
            Map<String, Object> word = taught.get(i);
            String kind = depth.kinds().get(i % depth.kinds().size());
            Block block = new Block().show(new Step(TEACH_WORD, null, null, forCard(word, depth)))
                    .ask(PathQuiz.ask(toWord(word), pool, kind, depth, random));
            blocks.add(block);
            wordBlocks.add(block);
        }

        // A word comes back later in the lesson, asked by its pronunciation.
        int revisited = (flags & MORE_PRONUNCIATION) != 0 ? taught.size() : Math.min(depth.revisitedWords(), taught.size());
        for (int i = 0; i < revisited; i++) {
            Question again = PathQuiz.ask(toWord(taught.get(i)), pool, PathQuiz.HANZI_TO_PINYIN, depth, random);
            if (again != null) insertAfter(blocks, wordBlocks.get(i), new Block().ask(again), random);
        }

        int characterLimit = (flags & MORE_CHARACTERS) != 0 ? 3 : 1;
        List<Map<String, Object>> characters = new ArrayList<>(list(content.get("characters")));
        Collections.shuffle(characters, random);
        for (Map<String, Object> character : characters.subList(0, Math.min(characterLimit, characters.size()))) {
            List<Question> asked = PathQuiz.forCharacters(List.of(character), characterPool, 1, random);
            if (asked.isEmpty()) continue;
            int index = indexOfWord(taught, String.valueOf(character.get("word")));
            insertAfter(blocks, index < 0 ? null : wordBlocks.get(index), new Block().ask(asked.get(0)), random);
        }

        // Each grammar note is followed by a question on the grammar of this lesson.
        List<Question> grammarQuestions = new ArrayList<>(PathQuiz.forPrepared(list(content.get("questions")), "grammar", Integer.MAX_VALUE, random));
        List<Map<String, Object>> points = list(content.get("points"));
        for (int i = 0; i < points.size(); i++) {
            Block block = new Block().show(new Step(TEACH_GRAMMAR, null, null, points.get(i)));
            if (!grammarQuestions.isEmpty()) block.ask(grammarQuestions.remove(0));
            if (i == points.size() - 1) grammarQuestions.forEach(block::ask);
            insertAfter(blocks, wordBlocks.isEmpty() ? null : wordBlocks.get(0), block, random);
        }

        List<Map<String, Object>> lines = list(content.get("lines"));
        int speakLimit = Math.min((flags & MORE_SPEAKING) != 0 ? 2 : 1, lines.size());
        for (int i = 0; i < speakLimit; i++) {
            String zh = String.valueOf(lines.get(i).get("zh"));
            Block after = null;
            for (int w = 0; w < taught.size(); w++) {
                if (zh.contains(String.valueOf(taught.get(w).get("simplified")))) after = wordBlocks.get(w);
            }
            insertAfter(blocks, after != null ? after : wordBlocks.isEmpty() ? null : wordBlocks.get(0),
                    new Block().show(new Step(SPEAK, null, i, withSyllables(lines.get(i)))), random);
        }

        return flatten(blocks);
    }

    /** A random place anywhere after the given block, or anywhere at all if there is none. */
    private static void insertAfter(List<Block> blocks, Block after, Block block, Random random) {
        int earliest = after == null ? 0 : blocks.indexOf(after) + 1;
        blocks.add(earliest + random.nextInt(blocks.size() - earliest + 1), block);
    }

    private static int indexOfWord(List<Map<String, Object>> words, String simplified) {
        for (int i = 0; i < words.size(); i++) {
            if (simplified.equals(String.valueOf(words.get(i).get("simplified")))) return i;
        }
        return -1;
    }

    /** Questions get their ids here, in the order they are asked. */
    private static Plan flatten(List<Block> blocks) {
        List<Step> steps = new ArrayList<>();
        List<Question> questions = new ArrayList<>();
        for (Block block : blocks) {
            int asked = 0;
            for (Step step : block.steps) {
                if (!QUESTION.equals(step.kind())) {
                    steps.add(step);
                    continue;
                }
                Question question = PathQuiz.withId(block.questions.get(asked++), questions.size());
                questions.add(question);
                steps.add(new Step(QUESTION, question.id(), null, null));
            }
        }
        return new Plan(steps, questions);
    }

    /** Set on a word whose meaning_vi and part_of_speech are those its level teaches (see PathLearningService). */
    public static final String LEVEL_MEANING = "level_meaning";

    public static Word toWord(Map<String, Object> item) {
        String meaning = text(item.get("meaning_vi"));
        String example = null;
        String exampleMeaning = null;
        if (item.get("example") instanceof Map<?, ?> sentence) {
            example = text(sentence.get("zh"));
            exampleMeaning = text(sentence.get("vi"));
        }
        return new Word(text(item.get("simplified")), text(item.get("pinyin")), meaning.isBlank() ? text(item.get("meaning_en")) : meaning,
                example, exampleMeaning, text(item.get("part_of_speech")));
    }

    /** The word as its card shows it: only the meanings of the level, and at HSK 1 no long example. */
    static Map<String, Object> forCard(Map<String, Object> word, KnowledgeDepth depth) {
        if (depth == KnowledgeDepth.LEGACY) return word;
        Map<String, Object> card = new java.util.LinkedHashMap<>(word);
        // A meaning of the level from the syllabus is already cut to what the level teaches.
        boolean ofLevel = Boolean.TRUE.equals(word.get(LEVEL_MEANING));
        for (String key : ofLevel ? List.of("meaning_en") : List.of("meaning_vi", "meaning_en")) {
            String meaning = text(word.get(key));
            if (!meaning.isBlank()) card.put(key, depth.cardMeaning(meaning));
        }
        if (word.get("example") instanceof Map<?, ?> sentence && !depth.showsExample(text(sentence.get("zh")))) card.remove("example");
        return card;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** The line as stored, plus one entry per character with its syllable, for marking each character when spoken. */
    private static Map<String, Object> withSyllables(Map<String, Object> line) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(line);
        copy.put("chars", PinyinByChar.split(String.valueOf(line.get("zh")), line.get("pinyin") == null ? null : String.valueOf(line.get("pinyin"))));
        return copy;
    }
}
