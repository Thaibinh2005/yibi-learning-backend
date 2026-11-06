package com.yibi.backend.service.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Makes the questions of a node from its content. The same content and seed always give the same questions,
 * so an answer is graded by generating them again and no quiz has to be stored. No database access or AI call.
 */
public final class PathQuiz {

    private static final int OPTIONS = 4;
    private static final int MAX_MEANING_LENGTH = 60;
    /** Dictionary cross-references such as "dùng trong 傢伙[jia1 huo5]": unreadable, and they give the answer away. */
    private static final java.util.regex.Pattern CROSS_REFERENCE = java.util.regex.Pattern.compile("[\\p{IsHan}\\[\\]|]");

    private PathQuiz() { }

    public record Question(String id, String type, String skill, String prompt, String pinyin, String audioText,
                           List<String> options, String answer, String explanation) { }

    public record Word(String hanzi, String pinyin, String meaning) { }

    /** Questions on words: mostly meaning both ways, with some pronunciation. */
    public static List<Question> forWords(List<Word> words, List<Word> pool, int limit, Random random) {
        List<Word> chosen = new ArrayList<>(words);
        Collections.shuffle(chosen, random);
        List<Question> questions = new ArrayList<>();
        for (Word word : chosen) {
            if (questions.size() >= limit) break;
            Question question = switch (questions.size() % 3) {
                case 0 -> meaningToHanzi(word, pool, random);
                case 1 -> hanziToMeaning(word, pool, random);
                default -> hanziToPinyin(word, pool, random);
            };
            if (question == null) question = hanziToPinyin(word, pool, random);
            if (question != null) questions.add(withId(question, questions.size()));
        }
        return questions;
    }

    /** "Which character is in this word?" for each character, with the word as the clue. */
    public static List<Question> forCharacters(List<Map<String, Object>> characters, List<String> pool, int limit, Random random) {
        List<Map<String, Object>> chosen = new ArrayList<>(characters);
        Collections.shuffle(chosen, random);
        List<Question> questions = new ArrayList<>();
        for (Map<String, Object> item : chosen) {
            if (questions.size() >= limit) break;
            String hanzi = text(item.get("hanzi"));
            String word = text(item.get("word"));
            List<String> options = options(hanzi, pool, word, random);
            if (options == null) continue;
            String blanked = word.replace(hanzi, "＿");
            questions.add(new Question("q" + questions.size(), "CHARACTER", "characters",
                    "Điền chữ còn thiếu: " + blanked + " (" + shorten(text(item.get("meaning_vi"))) + ")",
                    text(item.get("pinyin")), null, options, hanzi,
                    word + " đọc là " + text(item.get("pinyin")) + "."));
        }
        return questions;
    }

    /** Questions written with the content (a passage, a dialogue, a grammar note); only the options are shuffled. */
    public static List<Question> forPrepared(List<Map<String, Object>> prepared, String skill, int limit, Random random) {
        List<Map<String, Object>> chosen = new ArrayList<>(prepared);
        Collections.shuffle(chosen, random);
        List<Question> questions = new ArrayList<>();
        for (Map<String, Object> item : chosen) {
            if (questions.size() >= limit) break;
            if (!(item.get("options") instanceof List<?> raw) || raw.size() < 2) continue;
            List<String> options = new ArrayList<>(raw.stream().map(String::valueOf).toList());
            String answer = text(item.get("answer"));
            if (!options.contains(answer)) continue;
            Collections.shuffle(options, random);
            questions.add(new Question("q" + questions.size(), "MULTIPLE_CHOICE", skill, text(item.get("question")),
                    null, null, options, answer, text(item.get("explanation"))));
        }
        return questions;
    }

    /** Joins question sets into one quiz of at most limit questions, with ids unique inside it. */
    public static List<Question> mix(List<Question> questions, int limit, Random random) {
        List<Question> shuffled = new ArrayList<>(questions);
        Collections.shuffle(shuffled, random);
        List<Question> result = new ArrayList<>();
        for (Question question : shuffled.subList(0, Math.min(limit, shuffled.size()))) {
            result.add(withId(question, result.size()));
        }
        return result;
    }

    public static int scorePercent(int correct, int total) {
        return total == 0 ? 100 : Math.round(correct * 100f / total);
    }

    private static Question meaningToHanzi(Word word, List<Word> pool, Random random) {
        if (shorten(word.meaning()).isBlank()) return null;
        List<String> options = options(word.hanzi(), pool.stream().map(Word::hanzi).toList(), "", random);
        if (options == null) return null;
        return new Question("", "MEANING_TO_HANZI", "vocabulary", "Từ nào có nghĩa là “" + shorten(word.meaning()) + "”?",
                null, null, options, word.hanzi(), word.hanzi() + " (" + word.pinyin() + "): " + word.meaning());
    }

    private static Question hanziToMeaning(Word word, List<Word> pool, Random random) {
        if (shorten(word.meaning()).isBlank()) return null;
        List<String> meanings = pool.stream().filter(other -> !other.hanzi().equals(word.hanzi()))
                .map(other -> shorten(other.meaning())).filter(meaning -> !meaning.isBlank()).toList();
        String answer = shorten(word.meaning());
        List<String> options = options(answer, meanings, "", random);
        if (options == null) return null;
        return new Question("", "HANZI_TO_MEANING", "vocabulary", word.hanzi() + " có nghĩa là gì?", word.pinyin(),
                word.hanzi(), options, answer, word.hanzi() + " (" + word.pinyin() + "): " + word.meaning());
    }

    private static Question hanziToPinyin(Word word, List<Word> pool, Random random) {
        if (word.pinyin().isBlank()) return null;
        List<String> options = options(word.pinyin(), pool.stream().map(Word::pinyin).toList(), "", random);
        if (options == null) return null;
        return new Question("", "HANZI_TO_PINYIN", "phonetics", word.hanzi() + " đọc là gì?", null, null, options,
                word.pinyin(), word.hanzi() + " đọc là " + word.pinyin() + ".");
    }

    /** The answer plus three different wrong options; null when the pool cannot supply them. */
    private static List<String> options(String answer, List<String> pool, String excludedLetters, Random random) {
        if (answer.isBlank()) return null;
        List<String> candidates = new ArrayList<>(pool);
        Collections.shuffle(candidates, random);
        Set<String> options = new LinkedHashSet<>();
        options.add(answer);
        for (String candidate : candidates) {
            if (options.size() == OPTIONS) break;
            if (!candidate.isBlank() && !excludedLetters.contains(candidate)) options.add(candidate);
        }
        if (options.size() < OPTIONS) return null;
        List<String> result = new ArrayList<>(options);
        Collections.shuffle(result, random);
        return result;
    }

    private static Question withId(Question q, int index) {
        return new Question("q" + index, q.type(), q.skill(), q.prompt(), q.pinyin(), q.audioText(), q.options(), q.answer(), q.explanation());
    }

    /** First senses only: a dictionary entry can run far longer than an answer button. */
    public static String shorten(String meaning) {
        String[] senses = meaning.split("[;；]");
        StringBuilder result = new StringBuilder();
        for (String sense : senses) {
            String trimmed = sense.trim();
            if (trimmed.isEmpty() || CROSS_REFERENCE.matcher(trimmed).find()) continue;
            if (result.length() > 0 && result.length() + trimmed.length() + 2 > MAX_MEANING_LENGTH) break;
            if (result.length() > 0) result.append("; ");
            result.append(trimmed);
            if (result.length() >= 24) break;
        }
        return result.toString();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
