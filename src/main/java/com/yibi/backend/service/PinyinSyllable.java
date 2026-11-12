package com.yibi.backend.service;

import java.util.List;

/**
 * Splits a numbered pinyin syllable ("nü3", "zhong1") into initial, final and tone.
 * The final is the spelling used after an initial: iou/uei/uen are written iu/ui/un, and u after j, q, x is ü.
 * A syllable written with y or w is read back to its final (yi → i, wu → u, yue → üe).
 */
public record PinyinSyllable(String initial, String finalSound, int tone, String marked) {

    private static final List<String> INITIALS = List.of(
            "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "r", "z", "c", "s");

    /** @param numbered lowercase pinyin ending in a tone digit 1-5, ü written as ü or v */
    public static PinyinSyllable parse(String numbered) {
        String s = numbered.replace('v', 'ü');
        char last = s.charAt(s.length() - 1);
        if (last < '1' || last > '5') {
            throw new IllegalArgumentException("No tone digit: " + numbered);
        }
        int tone = last - '0';
        String body = s.substring(0, s.length() - 1);
        String marked = mark(body, tone);

        String initial = "";
        String rest = body;
        if (body.startsWith("y") || body.startsWith("w")) {
            rest = fromSemivowel(body);
        } else {
            for (String candidate : INITIALS) {
                if (body.startsWith(candidate) && body.length() > candidate.length()) {
                    initial = candidate;
                    rest = body.substring(candidate.length());
                    break;
                }
            }
            if (rest.startsWith("u") && (initial.equals("j") || initial.equals("q") || initial.equals("x"))) {
                rest = "ü" + rest.substring(1);
            }
        }
        return new PinyinSyllable(initial, shorten(rest), tone, marked);
    }

    private static String fromSemivowel(String body) {
        String rest = body.substring(1);
        if (body.startsWith("y")) {
            if (rest.startsWith("u") || rest.startsWith("ü")) return "ü" + rest.substring(1);
            return rest.startsWith("i") ? rest : "i" + rest;
        }
        return rest.startsWith("u") ? rest : "u" + rest;
    }

    private static String shorten(String f) {
        return switch (f) {
            case "iou" -> "iu";
            case "uei" -> "ui";
            case "uen" -> "un";
            default -> f;
        };
    }

    /** The tone mark goes on a or e, else on the o of ou, else on the last vowel. */
    static String mark(String body, int tone) {
        if (tone == 5) return body;
        String vowels = "aeiouü";
        int at = -1;
        if (body.indexOf('a') >= 0) at = body.indexOf('a');
        else if (body.indexOf('e') >= 0) at = body.indexOf('e');
        else if (body.contains("ou")) at = body.indexOf('o');
        else {
            for (int i = body.length() - 1; i >= 0; i--) {
                if (vowels.indexOf(body.charAt(i)) >= 0) { at = i; break; }
            }
        }
        if (at < 0) return body;
        String[][] marks = {
                {"a", "ā", "á", "ǎ", "à"}, {"e", "ē", "é", "ě", "è"}, {"i", "ī", "í", "ǐ", "ì"},
                {"o", "ō", "ó", "ǒ", "ò"}, {"u", "ū", "ú", "ǔ", "ù"}, {"ü", "ǖ", "ǘ", "ǚ", "ǜ"}};
        String ch = String.valueOf(body.charAt(at));
        for (String[] row : marks) {
            if (row[0].equals(ch)) return body.substring(0, at) + row[tone] + body.substring(at + 1);
        }
        return body;
    }
}
