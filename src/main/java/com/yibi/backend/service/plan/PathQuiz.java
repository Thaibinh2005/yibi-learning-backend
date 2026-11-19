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

    public static final String MEANING_TO_HANZI = "MEANING_TO_HANZI";
    public static final String HANZI_TO_MEANING = "HANZI_TO_MEANING";
    public static final String HANZI_TO_PINYIN = "HANZI_TO_PINYIN";
    /** The word blanked out of its example sentence: how it is used and what it goes with. */
    public static final String WORD_IN_SENTENCE = "WORD_IN_SENTENCE";
    /** Three real meanings of a word and one that is not: the range and shades of its meaning. */
    public static final String NOT_A_MEANING = "NOT_A_MEANING";

    private static final int OPTIONS = 4;
    private static final int MAX_MEANING_LENGTH = 60;
    /** Dictionary cross-references such as "dùng trong 傢伙[jia1 huo5]": unreadable, and they give the answer away. */
    private static final java.util.regex.Pattern CROSS_REFERENCE = java.util.regex.Pattern.compile("[\\p{IsHan}\\[\\]|]");

    private PathQuiz() { }

    public record Question(String id, String type, String skill, String prompt, String pinyin, String audioText,
                           List<String> options, String answer, String explanation) { }

    /**
     * @param example        a sentence using the word, or null; exampleMeaning is its translation
     * @param partOfSpeech   the syllabus word class such as "动" or "形、介", or null
     */
    public record Word(String hanzi, String pinyin, String meaning, String example, String exampleMeaning, String partOfSpeech) {
        public Word(String hanzi, String pinyin, String meaning) {
            this(hanzi, pinyin, meaning, null, null, null);
        }

        public Word(String hanzi, String pinyin, String meaning, String example, String exampleMeaning) {
            this(hanzi, pinyin, meaning, example, exampleMeaning, null);
        }

        /** The main word class, the first one listed: "形" for "形、介、（动、量）". */
        public String mainClass() {
            if (partOfSpeech == null) return "";
            return partOfSpeech.codePoints().filter(Character::isIdeographic).limit(1)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        }
    }

    /** Questions on words at HSK 2–3 depth: meaning both ways, use in a sentence and some pronunciation. */
    public static List<Question> forWords(List<Word> words, List<Word> pool, int limit, Random random) {
        return forWords(words, pool, limit, KnowledgeDepth.EXPAND, random);
    }

    /** Questions on words, of the kinds the level calls for; up to HSK 6 some ask the pronunciation too. */
    public static List<Question> forWords(List<Word> words, List<Word> pool, int limit, KnowledgeDepth depth, Random random) {
        List<String> kinds = new ArrayList<>(depth.kinds());
        if (depth != KnowledgeDepth.DEEP) kinds.add(HANZI_TO_PINYIN);
        List<Word> chosen = new ArrayList<>(words);
        Collections.shuffle(chosen, random);
        List<Question> questions = new ArrayList<>();
        for (Word word : chosen) {
            if (questions.size() >= limit) break;
            Question question = ask(word, pool, kinds.get(questions.size() % kinds.size()), depth, random);
            if (question != null) questions.add(withId(question, questions.size()));
        }
        return questions;
    }

    /** One question on a word at HSK 2–3 depth. */
    public static Question ask(Word word, List<Word> pool, String kind, Random random) {
        return ask(word, pool, kind, KnowledgeDepth.EXPAND, random);
    }

    /**
     * One question on a word. A kind the word cannot support falls back to a simpler one: no example sentence or too
     * few meanings gives a meaning question, and no usable meaning gives a pronunciation question; null if all fail.
     */
    public static Question ask(Word word, List<Word> pool, String kind, KnowledgeDepth depth, Random random) {
        Question question = switch (kind) {
            case WORD_IN_SENTENCE -> wordInSentence(word, pool, depth, random);
            case NOT_A_MEANING -> notAMeaning(word, pool, random);
            case MEANING_TO_HANZI -> meaningToHanzi(word, pool, depth, random);
            case HANZI_TO_MEANING -> hanziToMeaning(word, pool, depth, random);
            default -> null;
        };
        // A word without an example may still have several meanings, and the other way round: try the other
        // question in context the level asks before falling back to a plain meaning question.
        if (question == null && WORD_IN_SENTENCE.equals(kind) && depth.kinds().contains(NOT_A_MEANING)) {
            question = notAMeaning(word, pool, random);
        }
        if (question == null && NOT_A_MEANING.equals(kind) && depth.kinds().contains(WORD_IN_SENTENCE)) {
            question = wordInSentence(word, pool, depth, random);
        }
        if (question == null && (WORD_IN_SENTENCE.equals(kind) || NOT_A_MEANING.equals(kind))) {
            question = hanziToMeaning(word, pool, depth, random);
        }
        return question != null ? question : hanziToPinyin(word, pool, random);
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

    private static Question meaningToHanzi(Word word, List<Word> pool, KnowledgeDepth depth, Random random) {
        String meaning = depth.answerMeaning(word.meaning());
        if (meaning.isBlank()) return null;
        List<String> options = options(word.hanzi(), pool.stream().map(Word::hanzi).toList(), "", random);
        if (options == null) return null;
        return new Question("", MEANING_TO_HANZI, "vocabulary", "Từ nào có nghĩa là “" + meaning + "”?",
                null, null, options, word.hanzi(), explain(word, depth));
    }

    private static Question hanziToMeaning(Word word, List<Word> pool, KnowledgeDepth depth, Random random) {
        String answer = depth.answerMeaning(word.meaning());
        if (answer.isBlank()) return null;
        // Wrong options carry as many meanings as the answer, so its length gives nothing away.
        List<String> meanings = pool.stream().filter(other -> !other.hanzi().equals(word.hanzi()))
                .map(other -> depth.answerMeaning(other.meaning())).filter(meaning -> !meaning.isBlank()).toList();
        List<String> options = options(answer, meanings, "", random);
        if (options == null) return null;
        return new Question("", HANZI_TO_MEANING, "vocabulary", word.hanzi() + " có nghĩa là gì?", word.pinyin(),
                word.hanzi(), options, answer, explain(word, depth));
    }

    /**
     * "Fill in the blank" with the word's own example sentence. The wrong options are, when the pool has enough of them,
     * words of the same class and length, so the grammar of the sentence alone does not give the answer away.
     */
    private static Question wordInSentence(Word word, List<Word> pool, KnowledgeDepth depth, Random random) {
        String sentence = word.example() == null ? "" : word.example().trim();
        if (sentence.isEmpty() || !sentence.contains(word.hanzi()) || !depth.showsExample(sentence)) return null;
        String blanked = sentence.replaceFirst(java.util.regex.Pattern.quote(word.hanzi()), "＿＿");
        int length = word.hanzi().codePointCount(0, word.hanzi().length());
        String wordClass = word.mainClass();
        List<Word> others = pool.stream()
                .filter(other -> !other.hanzi().equals(word.hanzi()) && !sentence.contains(other.hanzi())).toList();
        java.util.function.Predicate<Word> sameLength = other -> other.hanzi().codePointCount(0, other.hanzi().length()) == length;
        java.util.function.Predicate<Word> sameClass = other -> !wordClass.isEmpty() && wordClass.equals(other.mainClass());
        List<String> candidates = others.stream().map(Word::hanzi).distinct().toList();
        for (java.util.function.Predicate<Word> filter : List.of(sameClass.and(sameLength), sameClass, sameLength)) {
            List<String> matching = others.stream().filter(filter).map(Word::hanzi).distinct().toList();
            if (matching.size() >= OPTIONS - 1) {
                candidates = matching;
                break;
            }
        }
        List<String> options = options(word.hanzi(), candidates, "", random);
        if (options == null) return null;
        String translation = word.exampleMeaning() == null || word.exampleMeaning().isBlank() ? "" : " (" + word.exampleMeaning().trim() + ")";
        return new Question("", WORD_IN_SENTENCE, "vocabulary", "Chọn từ điền vào chỗ trống: " + blanked, null, null,
                options, word.hanzi(), sentence + translation + " " + explain(word, depth));
    }

    /** "Which is not a meaning of the word?" Needs at least three meanings; the odd one out is a meaning of another word. */
    private static Question notAMeaning(Word word, List<Word> pool, Random random) {
        List<String> own = KnowledgeDepth.senses(word.meaning()).stream()
                .filter(sense -> sense.length() <= KnowledgeDepth.MAX_ANSWER_LENGTH).toList();
        if (own.size() < OPTIONS - 1) return null;
        List<String> shown = new ArrayList<>(own.subList(0, Math.min(own.size(), 5)));
        Collections.shuffle(shown, random);
        shown = new ArrayList<>(shown.subList(0, OPTIONS - 1));
        List<String> foreign = new ArrayList<>();
        for (Word other : pool) {
            if (other.hanzi().equals(word.hanzi())) continue;
            for (String sense : KnowledgeDepth.senses(other.meaning())) {
                boolean overlaps = own.stream().anyMatch(mine -> mine.contains(sense) || sense.contains(mine));
                if (!overlaps && sense.length() <= KnowledgeDepth.MAX_ANSWER_LENGTH && !foreign.contains(sense)) foreign.add(sense);
            }
        }
        if (foreign.isEmpty()) return null;
        String answer = foreign.get(random.nextInt(foreign.size()));
        shown.add(answer);
        Collections.shuffle(shown, random);
        return new Question("", NOT_A_MEANING, "vocabulary", word.hanzi() + " KHÔNG có nghĩa nào dưới đây?", word.pinyin(),
                word.hanzi(), shown, answer, word.hanzi() + " (" + word.pinyin() + "): " + word.meaning());
    }

    /** The word with the meanings of the level, as shown after an answer. */
    private static String explain(Word word, KnowledgeDepth depth) {
        return word.hanzi() + " (" + word.pinyin() + "): " + depth.cardMeaning(word.meaning());
    }

    private static Question hanziToPinyin(Word word, List<Word> pool, Random random) {
        if (word.pinyin().isBlank()) return null;
        List<String> options = options(word.pinyin(), pool.stream().map(Word::pinyin).toList(), "", random);
        if (options == null) return null;
        return new Question("", HANZI_TO_PINYIN, "phonetics", word.hanzi() + " đọc là gì?", null, null, options,
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

    public static Question withId(Question q, int index) {
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
