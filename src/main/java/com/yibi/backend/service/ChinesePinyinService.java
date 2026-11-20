package com.yibi.backend.service;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.yibi.backend.service.VocabularyLookupIndex.DictionaryWord;
import lombok.RequiredArgsConstructor;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits a Chinese sentence into words and writes its pinyin without AI.
 *
 * <p>Words are found by longest match against the HSK bank, so a character that reads differently inside a word
 * (行 in 银行 and 行走) gets the word's reading. A character outside every known word falls back to pinyin4j; when that
 * character has several readings the sentence is flagged for the learner to check. 一 and 不 get their tone change
 * from the next syllable, because the bank itself writes them both ways (一起 is yīqǐ at HSK 1 and yìqǐ at HSK 2).</p>
 */
@Service
@RequiredArgsConstructor
public class ChinesePinyinService {

    public static final String POLYPHONE = "POLYPHONE";
    private static final int MAX_WORD = 8;
    /** After 一 these make a number or a date (一月, 十一号), where 一 keeps its first tone. */
    private static final String NUMBER_CONTEXT = "月号日楼百千万亿十零〇一二三四五六七八九第";

    /** One piece of the sentence; {@code py}, {@code id} and {@code hsk} are null for punctuation and other text. */
    public record Token(String w, String py, Long id, String hsk) { }

    public record Analysis(String pinyin, List<Token> words, Set<String> flags) { }

    private final VocabularyLookupIndex lookupIndex;

    public Analysis analyze(String text) {
        return analyze(text, lookupIndex.dictionary());
    }

    public static Analysis analyze(String text, Map<String, DictionaryWord> dictionary) {
        String simplified = ZhConverterUtil.toSimple(text);
        String lookup = simplified.length() == text.length() ? simplified : text;
        List<Token> tokens = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();
        int i = 0;
        while (i < text.length()) {
            if (!isHan(text.codePointAt(i))) {
                int j = i;
                while (j < text.length() && !isHan(text.codePointAt(j))) j += Character.charCount(text.codePointAt(j));
                tokens.add(new Token(text.substring(i, j), null, null, null));
                i = j;
                continue;
            }
            int matched = 0;
            for (int len = Math.min(MAX_WORD, text.length() - i); len >= 1; len--) {
                String piece = lookup.substring(i, i + len);
                if (!allHan(piece)) continue;
                DictionaryWord word = dictionary.get(piece);
                if (word != null) {
                    tokens.add(new Token(text.substring(i, i + len), word.pinyin().replace(" ", ""), word.recordId(), word.level()));
                    matched = len;
                    break;
                }
            }
            if (matched == 0) {
                int cp = text.codePointAt(i);
                matched = Character.charCount(cp);
                List<String> readings = readingsOf(lookup.codePointAt(i));
                if (readings.size() > 1) flags.add(POLYPHONE);
                tokens.add(new Token(text.substring(i, i + matched), readings.isEmpty() ? null : readings.get(0), null, null));
            }
            i += matched;
        }
        applyYiBu(tokens);
        return new Analysis(pinyinLine(tokens), tokens, flags);
    }

    /** 不 is bú before a fourth tone; 一 is yí before a fourth tone and yì before the others, except in numbers. */
    static void applyYiBu(List<Token> tokens) {
        for (int t = 0; t < tokens.size(); t++) {
            Token token = tokens.get(t);
            if (token.py() == null) continue;
            String w = token.w();
            char first = w.charAt(0);
            if (first != '一' && first != '不') continue;
            String rest;
            String nextChar;
            if (w.length() > 1) {
                rest = stripFirstSyllable(token.py(), first);
                nextChar = w.substring(1, 2);
            } else {
                Token next = t + 1 < tokens.size() ? tokens.get(t + 1) : null;
                if (next == null || next.py() == null) continue;
                rest = next.py();
                nextChar = next.w().substring(0, 1);
            }
            if (rest == null) continue;
            int tone = firstTone(rest);
            if (tone == 0) continue;
            String syllable;
            if (first == '不') {
                syllable = tone == 4 ? "bú" : "bù";
            } else {
                boolean number = NUMBER_CONTEXT.contains(nextChar)
                        || (t > 0 && tokens.get(t - 1).w().length() > 0
                        && NUMBER_CONTEXT.contains(tokens.get(t - 1).w().substring(tokens.get(t - 1).w().length() - 1)));
                if (number) continue;
                syllable = tone == 4 ? "yí" : "yì";
            }
            String py = w.length() > 1 ? syllable + rest : syllable;
            tokens.set(t, new Token(token.w(), py, token.id(), token.hsk()));
        }
    }

    /** "yīqǐ" -> "qǐ" when the word starts with 一; null when the reading does not start as expected. */
    private static String stripFirstSyllable(String pinyin, char first) {
        String lower = pinyin.toLowerCase();
        String[] heads = first == '一' ? new String[]{"yī", "yí", "yì", "yi"} : new String[]{"bù", "bú", "bu"};
        for (String head : heads) {
            if (lower.startsWith(head)) return pinyin.substring(head.length());
        }
        return null;
    }

    /** Tone of the first marked vowel: 1-4, or 0 when there is none (neutral tone or no pinyin). */
    static int firstTone(String pinyin) {
        String[] marks = {"āēīōūǖĀĒĪŌŪǕ", "áéíóúǘÁÉÍÓÚǗ", "ǎěǐǒǔǚǍĚǏǑǓǙ", "àèìòùǜÀÈÌÒÙǛ"};
        for (char c : pinyin.toCharArray()) {
            for (int tone = 0; tone < 4; tone++) {
                if (marks[tone].indexOf(c) >= 0) return tone + 1;
            }
        }
        return 0;
    }

    private static String pinyinLine(List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        for (Token token : tokens) {
            if (token.py() != null) {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ' && out.charAt(out.length() - 1) != '"') out.append(' ');
                out.append(token.py());
            } else {
                String text = halfWidth(token.w());
                if (text.isBlank()) {
                    if (!out.isEmpty()) out.append(' ');
                    continue;
                }
                out.append(text.strip());
            }
        }
        return out.toString().replaceAll(" {2,}", " ").trim();
    }

    private static String halfWidth(String text) {
        return text.replace('，', ',').replace('。', '.').replace('！', '!').replace('？', '?').replace('：', ':')
                .replace('；', ';').replace('、', ',').replace('（', '(').replace('）', ')')
                .replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'');
    }

    private static List<String> readingsOf(int codePoint) {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setToneType(HanyuPinyinToneType.WITH_TONE_MARK);
        format.setVCharType(HanyuPinyinVCharType.WITH_U_UNICODE);
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        try {
            String[] found = Character.isBmpCodePoint(codePoint)
                    ? PinyinHelper.toHanyuPinyinStringArray((char) codePoint, format) : null;
            if (found == null) return List.of();
            return new ArrayList<>(new LinkedHashSet<>(List.of(found)));
        } catch (BadHanyuPinyinOutputFormatCombination ex) {
            return List.of();
        }
    }

    static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static boolean allHan(String text) {
        return text.codePoints().allMatch(ChinesePinyinService::isHan);
    }
}
