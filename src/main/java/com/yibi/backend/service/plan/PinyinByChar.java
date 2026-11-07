package com.yibi.backend.service.plan;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Puts a pinyin syllable over each character of a sentence, so a speaking step can colour them one by one.
 * The sentence's own pinyin is split into syllables first; only if their number does not match the characters
 * does each character get its dictionary reading instead, which may then miss a tone change such as 一 or 不.
 */
public final class PinyinByChar {

    private static final Pattern SYLLABLE = Pattern.compile(
            "(?:zh|ch|sh|[bpmfdtnlgkhjqxrzcsyw])?"
                    + "(?:iang|iong|uang|ang|eng|ing|ong|iao|ian|uai|uan|van|ai|ei|ao|ou|an|en|er|ia|ie|iu|in|ua|uo|ui|un|ve|vn|ue|a|o|e|i|u|v)");
    private static final Pattern WORD_BREAK = Pattern.compile("[\\s,.;:!?'’\"“”()\\-—…·，。；：！？、（）]+");
    private static final int LONGEST = 6;

    private PinyinByChar() { }

    /** One entry per character of the sentence; characters that are not Chinese get an empty reading. */
    public static List<Map<String, String>> split(String sentence, String pinyin) {
        List<String> characters = sentence.codePoints().mapToObj(Character::toString).toList();
        long han = characters.stream().filter(PinyinByChar::isHan).count();
        List<String> syllables = syllables(pinyin == null ? "" : pinyin);
        boolean fits = syllables.size() == han;
        List<Map<String, String>> result = new ArrayList<>();
        int next = 0;
        for (String character : characters) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("han", character);
            entry.put("pinyin", !isHan(character) ? "" : fits ? syllables.get(next++) : dictionary(character));
            result.add(entry);
        }
        return result;
    }

    /** The syllables of a pinyin text, keeping its tone marks and capitals. Erhua "r" counts as the syllable of 儿. */
    static List<String> syllables(String pinyin) {
        List<String> found = new ArrayList<>();
        for (String word : WORD_BREAK.split(Normalizer.normalize(pinyin.trim(), Normalizer.Form.NFC))) {
            if (word.isEmpty()) continue;
            List<String> parts = splitWord(word, plain(word), 0);
            if (parts == null) return List.of();
            found.addAll(parts);
        }
        return found;
    }

    /** Longest syllable first, backing off when the rest of the word cannot be split. */
    private static List<String> splitWord(String word, String plain, int from) {
        if (from == plain.length()) return new ArrayList<>();
        for (int length = Math.min(LONGEST, plain.length() - from); length >= 1; length--) {
            String candidate = plain.substring(from, from + length);
            // Erhua: the "r" of 儿, joined to a syllable or written apart, as in "zhè'r" or "huǒ r".
            boolean erhua = candidate.equals("r");
            if (!erhua && !SYLLABLE.matcher(candidate).matches()) continue;
            List<String> rest = splitWord(word, plain, from + length);
            if (rest != null) {
                rest.add(0, word.substring(from, from + length));
                return rest;
            }
        }
        return null;
    }

    /** Same length as the word: tone marks removed, ü written v, lower case. */
    private static String plain(String word) {
        StringBuilder out = new StringBuilder(word.length());
        for (char c : word.toCharArray()) {
            if ("üǖǘǚǜÜǕǗǙǛ".indexOf(c) >= 0) {
                out.append('v');
            } else {
                String base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
                out.append(base.isEmpty() ? c : Character.toLowerCase(base.charAt(0)));
            }
        }
        return out.toString();
    }

    private static String dictionary(String character) {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setToneType(HanyuPinyinToneType.WITH_TONE_MARK);
        format.setVCharType(HanyuPinyinVCharType.WITH_U_UNICODE);
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        try {
            String[] readings = PinyinHelper.toHanyuPinyinStringArray(character.charAt(0), format);
            // pinyin4j writes the third tone with a breve (ă); pinyin uses a caron (ǎ).
            return readings == null || readings.length == 0 ? "" : readings[0].replace('ă', 'ǎ').replace('ĕ', 'ě').replace('ĭ', 'ǐ').replace('ŏ', 'ǒ').replace('ŭ', 'ǔ');
        } catch (Exception ex) {
            return "";
        }
    }

    static boolean isHan(String character) {
        return !character.isEmpty() && Character.UnicodeScript.of(character.codePointAt(0)) == Character.UnicodeScript.HAN;
    }
}
