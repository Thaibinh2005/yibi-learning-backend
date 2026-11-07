package com.yibi.backend.service.plan;

import com.github.houbb.opencc4j.util.ZhConverterUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lines up what was heard with the sentence, character by character, so each one can be marked right or wrong.
 * The recognizer may write traditional characters or digits; both are brought to the sentence's form first.
 */
public final class SpeechAlignment {

    public static final String RIGHT = "ok";
    public static final String WRONG = "bad";
    public static final String NOT_SPOKEN = "skip";

    /** @param marks one per character of the sentence, in order; punctuation is marked NOT_SPOKEN */
    public record Result(double accuracy, List<Map<String, String>> marks) { }

    private static final String DIGITS = "零一二三四五六七八九";

    private SpeechAlignment() { }

    public static Result align(String sentence, String heard) {
        List<String> characters = sentence.codePoints().mapToObj(Character::toString).toList();
        List<Integer> spoken = new ArrayList<>();
        StringBuilder want = new StringBuilder();
        for (int i = 0; i < characters.size(); i++) {
            if (PinyinByChar.isHan(characters.get(i))) {
                spoken.add(i);
                want.append(characters.get(i));
            }
        }
        String got = normalize(heard);
        boolean[] matched = matches(want.toString(), got);
        List<Map<String, String>> marks = new ArrayList<>();
        int position = 0;
        for (int i = 0; i < characters.size(); i++) {
            String mark = spoken.contains(i) ? (matched[position++] ? RIGHT : WRONG) : NOT_SPOKEN;
            marks.add(Map.of("han", characters.get(i), "mark", mark));
        }
        int distance = distance(want.toString(), got);
        double accuracy = want.isEmpty() ? 0 : Math.max(0, 1 - distance / (double) want.length());
        return new Result(accuracy, marks);
    }

    /** Chinese characters only, simplified, digits as characters. */
    static String normalize(String heard) {
        if (heard == null) return "";
        StringBuilder out = new StringBuilder();
        String simplified = ZhConverterUtil.toSimple(heard);
        simplified.codePoints().forEach(point -> {
            if (point >= '0' && point <= '9') out.append(DIGITS.charAt(point - '0'));
            else if (PinyinByChar.isHan(Character.toString(point))) out.appendCodePoint(point);
        });
        return out.toString();
    }

    /** Which characters of the sentence an optimal alignment pairs with the same character heard. */
    static boolean[] matches(String want, String got) {
        int[][] cost = table(want, got);
        boolean[] matched = new boolean[want.length()];
        int i = want.length();
        int j = got.length();
        while (i > 0 && j > 0) {
            if (want.charAt(i - 1) == got.charAt(j - 1) && cost[i][j] == cost[i - 1][j - 1]) {
                matched[--i] = true;
                j--;
            } else if (cost[i][j] == cost[i - 1][j - 1] + 1) {
                i--;
                j--;
            } else if (cost[i][j] == cost[i - 1][j] + 1) {
                i--;
            } else {
                j--;
            }
        }
        return matched;
    }

    private static int distance(String want, String got) {
        return table(want, got)[want.length()][got.length()];
    }

    private static int[][] table(String want, String got) {
        int[][] cost = new int[want.length() + 1][got.length() + 1];
        for (int i = 0; i <= want.length(); i++) cost[i][0] = i;
        for (int j = 0; j <= got.length(); j++) cost[0][j] = j;
        for (int i = 1; i <= want.length(); i++) {
            for (int j = 1; j <= got.length(); j++) {
                int same = want.charAt(i - 1) == got.charAt(j - 1) ? 0 : 1;
                cost[i][j] = Math.min(Math.min(cost[i - 1][j] + 1, cost[i][j - 1] + 1), cost[i - 1][j - 1] + same);
            }
        }
        return cost;
    }
}
