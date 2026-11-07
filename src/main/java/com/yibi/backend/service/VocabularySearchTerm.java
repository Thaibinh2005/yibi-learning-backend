package com.yibi.backend.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What the learner typed, in the forms the lookup compares against.
 *
 * @param text    trimmed and lower case, compared with the characters of a word
 * @param pinyin  letters only, so "xué xí", "xue2 xi2" and "xuexi" are the same; "v" stands for "ü"
 * @param word    words of a Vietnamese meaning, keeping the accents only if the learner typed them
 * @param accented whether {@code word} still has its accents
 */
public record VocabularySearchTerm(String text, String pinyin, String word, boolean accented) {

    /** Breve, circumflex, horn, hook, dot below, tilde and "đ" never occur in pinyin, so "ăn" is not the syllable "an". */
    private static final Pattern VIETNAMESE_ONLY = Pattern.compile("[̛̣̂̃̆̉đ]");

    public static VocabularySearchTerm of(String input) {
        String text = Normalizer.normalize(input.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (text.codePoints().anyMatch(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN)) {
            return new VocabularySearchTerm(text, "", "", false);
        }
        String words = text.replaceAll("[\\p{Punct}\\s]+", " ").trim();
        String plain = stripAccents(words);
        String pinyin = VIETNAMESE_ONLY.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).find() ? "" : plain.replace('v', 'u').replaceAll("[^a-z]", "");
        return new VocabularySearchTerm(text, pinyin, plain.equals(words) ? plain : words, !plain.equals(words));
    }

    static String stripAccents(String value) {
        return Normalizer.normalize(value.replace('đ', 'd'), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
