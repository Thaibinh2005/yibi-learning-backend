package com.yibi.backend.service.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * How much of a word Yibi teaches and asks at each HSK level:
 * <ul>
 *   <li>HSK 1: one main meaning, common words, short examples.</li>
 *   <li>HSK 2–3: secondary meanings and how the word combines with others (filling it into a sentence).</li>
 *   <li>HSK 4–6: shades of meaning, structure and context.</li>
 *   <li>HSK 7–9: usage, semantics and nuance in depth.</li>
 * </ul>
 * The lesson card shows the meanings of the level, and the questions on a word follow {@link #kinds()}.
 *
 * <p>A quiz is graded by building it again from its seed, so a quiz started before these rules existed must be
 * rebuilt the old way. New seeds carry {@link #LEVEL_RULES}; a seed without it gets {@link #LEGACY}.
 */
public enum KnowledgeDepth {

    /** The questions as they were before depth by level: every level alike. Only for grading older seeds. */
    LEGACY(Integer.MAX_VALUE, 0, 2, List.of(PathQuiz.MEANING_TO_HANZI, PathQuiz.HANZI_TO_MEANING)),

    CORE(1, 12, 2, List.of(PathQuiz.MEANING_TO_HANZI, PathQuiz.HANZI_TO_MEANING)),
    EXPAND(2, 0, 2, List.of(PathQuiz.MEANING_TO_HANZI, PathQuiz.HANZI_TO_MEANING, PathQuiz.WORD_IN_SENTENCE)),
    NUANCE(3, 0, 2, List.of(PathQuiz.WORD_IN_SENTENCE, PathQuiz.HANZI_TO_MEANING, PathQuiz.NOT_A_MEANING, PathQuiz.MEANING_TO_HANZI)),
    DEEP(Integer.MAX_VALUE, 0, 1, List.of(PathQuiz.WORD_IN_SENTENCE, PathQuiz.NOT_A_MEANING, PathQuiz.WORD_IN_SENTENCE, PathQuiz.HANZI_TO_MEANING));

    /**
     * Marks a seed as built with depth by level. Older seeds were at most {@code Integer.MAX_VALUE}; this bit keeps
     * new ones apart and stays well inside the 53 bits a browser number holds exactly.
     */
    public static final long LEVEL_RULES = 1L << 32;
    /**
     * Marks a seed whose quiz asks the meanings and word classes the official syllabus introduces at the level
     * (data/hsk_level_meanings.json) instead of the dictionary ones; a quiz started before keeps the old meanings.
     */
    public static final long LEVEL_MEANINGS = 1L << 33;

    /** Longest meaning that still fits an answer button. */
    static final int MAX_ANSWER_LENGTH = 60;
    /** Dictionary cross-references such as "dùng trong 傢伙[jia1 huo5]": unreadable, and they give the answer away. */
    private static final Pattern CROSS_REFERENCE = Pattern.compile("[\\p{IsHan}\\[\\]|]");

    private final int senses;
    private final int maxExampleLength;
    private final int revisitedWords;
    private final List<String> kinds;

    KnowledgeDepth(int senses, int maxExampleLength, int revisitedWords, List<String> kinds) {
        this.senses = senses;
        this.maxExampleLength = maxExampleLength;
        this.revisitedWords = revisitedWords;
        this.kinds = kinds;
    }

    /** A new seed, marked so that grading rebuilds the quiz with depth by level. */
    public static long mark(long seed) {
        return seed | LEVEL_RULES | LEVEL_MEANINGS;
    }

    /** Whether the quiz with this seed asks the meanings of the level (see {@link #LEVEL_MEANINGS}). */
    public static boolean usesLevelMeanings(long seed) {
        return (seed & LEVEL_MEANINGS) != 0;
    }

    /** The depth a quiz with this seed was built with: by level for a marked seed, {@link #LEGACY} otherwise. */
    public static KnowledgeDepth forSeed(int hskLevel, long seed) {
        return (seed & LEVEL_RULES) != 0 ? of(hskLevel) : LEGACY;
    }

    public static KnowledgeDepth of(int hskLevel) {
        if (hskLevel <= 1) return CORE;
        if (hskLevel <= 3) return EXPAND;
        if (hskLevel <= 6) return NUANCE;
        return DEEP;
    }

    /** How many meanings the lesson card shows; every meaning from HSK 7. */
    public int senses() {
        return senses;
    }

    /** The questions asked on the words of a lesson, in turn: the first word gets the first kind, and so on. */
    public List<String> kinds() {
        return kinds;
    }

    /** How many words come back later in a lesson, asked by their pronunciation. */
    public int revisitedWords() {
        return revisitedWords;
    }

    /** Whether an example sentence is short enough to show at this level. */
    public boolean showsExample(String sentence) {
        return maxExampleLength == 0 || sentence.codePointCount(0, sentence.length()) <= maxExampleLength;
    }

    /** The meanings of the level, for the lesson card. */
    public String cardMeaning(String meaning) {
        if (this == LEGACY) return meaning;
        List<String> all = senses(meaning);
        if (all.isEmpty()) return meaning.trim();
        return String.join("; ", all.subList(0, Math.min(senses, all.size())));
    }

    /** The meanings of the level, short enough for an answer button. */
    public String answerMeaning(String meaning) {
        if (this == LEGACY) return PathQuiz.shorten(meaning);
        StringBuilder result = new StringBuilder();
        int taken = 0;
        for (String sense : senses(meaning)) {
            if (taken == senses) break;
            if (result.length() > 0 && result.length() + sense.length() + 2 > MAX_ANSWER_LENGTH) break;
            if (result.length() > 0) result.append("; ");
            result.append(sense);
            taken++;
        }
        return result.length() > MAX_ANSWER_LENGTH ? result.substring(0, MAX_ANSWER_LENGTH - 1).trim() + "…" : result.toString();
    }

    /** The separate meanings of a dictionary entry, without cross-references. */
    public static List<String> senses(String meaning) {
        List<String> result = new ArrayList<>();
        for (String sense : meaning.split("[;；]")) {
            String trimmed = sense.trim();
            if (!trimmed.isEmpty() && !CROSS_REFERENCE.matcher(trimmed).find() && !result.contains(trimmed)) result.add(trimmed);
        }
        return result;
    }
}
