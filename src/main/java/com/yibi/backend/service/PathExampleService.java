package com.yibi.backend.service;

import com.yibi.backend.service.plan.PinyinByChar;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives every word an example sentence with a Vietnamese translation where one exists. The path and lesson data
 * carry Tatoeba sentences, translated into English only, for part of their words (about half from HSK 6), while
 * hsk_vocabulary_examples holds AI-written sentences with a Vietnamese translation for most words
 * (generate_vocab_examples.py). A word without a Vietnamese example gets the AI sentence; if there is none, it keeps
 * its own sentence without the English translation, since the app is for Vietnamese learners, and with a dictionary
 * pinyin when it came without one.
 */
@Service
@RequiredArgsConstructor
public class PathExampleService {

    private static final String CJK = "[一-鿿]";

    private final JdbcTemplate jdbc;

    /** The node content with an example added to each word that has none; the content itself is not changed. */
    public Map<String, Object> withExamples(Map<String, Object> content, int hskLevel) {
        if (!(content.get("words") instanceof List<?>)) return content;
        Map<String, Object> copy = new LinkedHashMap<>(content);
        copy.put("words", withExamples(words(content.get("words")), hskLevel));
        return copy;
    }

    /** The words, each with a Vietnamese example where one exists and never an English translation. */
    public List<Map<String, Object>> withExamples(List<Map<String, Object>> words, int hskLevel) {
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> word : words) {
            if (!hasVietnameseExample(word) && word.get("simplified") != null) missing.add(String.valueOf(word.get("simplified")));
        }
        if (missing.isEmpty()) return words;
        Map<String, Map<String, Object>> found = lookup(missing, hskLevel >= 7 ? "7-9" : String.valueOf(hskLevel));
        List<Map<String, Object>> result = new ArrayList<>(words.size());
        for (Map<String, Object> word : words) {
            if (hasVietnameseExample(word)) {
                result.add(word);
                continue;
            }
            Map<String, Object> example = found.get(String.valueOf(word.get("simplified")));
            if (example != null) {
                Map<String, Object> filled = new LinkedHashMap<>(word);
                filled.put("example", example);
                result.add(filled);
            } else if (word.get("example") instanceof Map<?, ?> own && own.containsKey("en")) {
                Map<String, Object> sentence = new LinkedHashMap<>();
                own.forEach((key, value) -> { if (!"en".equals(key)) sentence.put(String.valueOf(key), value); });
                if (sentence.get("zh") != null && (sentence.get("pinyin") == null || String.valueOf(sentence.get("pinyin")).isBlank())) {
                    sentence.put("pinyin", reading(String.valueOf(sentence.get("zh"))));
                }
                Map<String, Object> kept = new LinkedHashMap<>(word);
                kept.put("example", sentence);
                result.add(kept);
            } else {
                result.add(word);
            }
        }
        return result;
    }

    /** A lesson payload whose vocabulary section gets Vietnamese examples; the payload itself is not changed. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> withLessonExamples(Map<String, Object> payload, int hskLevel) {
        if (payload == null || !(payload.get("sections") instanceof Map<?, ?> sections)
                || !(sections.get("vocabulary") instanceof List<?> vocabulary) || vocabulary.isEmpty()) {
            return payload;
        }
        Map<String, Object> sectionsCopy = new LinkedHashMap<>((Map<String, Object>) sections);
        sectionsCopy.put("vocabulary", withExamples((List<Map<String, Object>>) vocabulary, hskLevel));
        Map<String, Object> copy = new LinkedHashMap<>(payload);
        copy.put("sections", sectionsCopy);
        return copy;
    }

    /**
     * One sentence per word that contains the word and has a clean translation: one written for the same level
     * first (among them one written for the meaning the level teaches), then the oldest, so the same word always gets the same sentence and a quiz rebuilds identically.
     */
    private Map<String, Map<String, Object>> lookup(List<String> words, String levelCode) {
        Map<String, Map<String, Object>> found = new HashMap<>();
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT DISTINCT ON (v.simplified) v.simplified, e.sentence_zh, e.sentence_pinyin, e.translation_vi
                    FROM hsk_vocabulary_examples e
                    JOIN hsk_vocabulary_records v ON v.raw_record_id = e.raw_record_id
                    WHERE v.simplified = ANY (?) AND position(v.simplified IN e.sentence_zh) > 0
                      AND COALESCE(e.translation_vi, '') <> '' AND e.translation_vi !~ ?
                    ORDER BY v.simplified, (v.level_code = ?) DESC, (e.source = 'level_meaning') DESC, e.id
                    """);
            Array array = connection.createArrayOf("text", words.toArray());
            statement.setArray(1, array);
            statement.setString(2, CJK);
            statement.setString(3, levelCode);
            return statement;
        }, rs -> {
            Map<String, Object> example = new LinkedHashMap<>();
            example.put("zh", rs.getString("sentence_zh"));
            if (rs.getString("sentence_pinyin") != null) example.put("pinyin", rs.getString("sentence_pinyin"));
            example.put("vi", rs.getString("translation_vi"));
            example.put("source", "ai");
            found.put(rs.getString("simplified"), example);
        });
        return found;
    }

    /**
     * A pinyin line for a sentence that came without one: each character's dictionary reading, syllables apart and
     * punctuation kept. It can miss a polyphone's reading in context or a tone change of 一 and 不.
     */
    static String reading(String sentence) {
        StringBuilder out = new StringBuilder();
        boolean afterSyllable = false;
        for (Map<String, String> character : PinyinByChar.split(sentence, null)) {
            String pinyin = character.get("pinyin");
            if (pinyin == null || pinyin.isEmpty()) {
                String han = character.get("han");
                // A letter or digit after a syllable is a word of its own; punctuation stays joined.
                if (afterSyllable && Character.isLetterOrDigit(han.codePointAt(0))) out.append(' ');
                out.append(han);
                afterSyllable = false;
            } else {
                afterSyllable = true;
                if (!out.isEmpty() && !Character.isWhitespace(out.charAt(out.length() - 1))) out.append(' ');
                out.append(pinyin);
            }
        }
        return out.toString().trim();
    }

    private static boolean hasVietnameseExample(Map<String, Object> word) {
        return word.get("example") instanceof Map<?, ?> example && example.get("zh") != null
                && !String.valueOf(example.get("zh")).isBlank()
                && example.get("vi") != null && !String.valueOf(example.get("vi")).isBlank();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> words(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
