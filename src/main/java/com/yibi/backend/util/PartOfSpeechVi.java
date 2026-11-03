package com.yibi.backend.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts HSK source part-of-speech labels (e.g. "量、（名）") into Vietnamese
 * display labels (e.g. "Lượng từ, (Danh từ)").
 */
public final class PartOfSpeechVi {

    // Ordered longest-first so compound tags (数量, 拟声) win over single chars.
    private static final Map<String, String> TAGS = new LinkedHashMap<>();
    static {
        TAGS.put("数量", "Số lượng từ");
        TAGS.put("拟声", "Từ tượng thanh");
        TAGS.put("前缀", "Tiền tố");
        TAGS.put("后缀", "Hậu tố");
        TAGS.put("名", "Danh từ");
        TAGS.put("动", "Động từ");
        TAGS.put("形", "Tính từ");
        TAGS.put("副", "Phó từ");
        TAGS.put("代", "Đại từ");
        TAGS.put("数", "Số từ");
        TAGS.put("量", "Lượng từ");
        TAGS.put("介", "Giới từ");
        TAGS.put("连", "Liên từ");
        TAGS.put("助", "Trợ từ");
        TAGS.put("叹", "Thán từ");
    }

    private PartOfSpeechVi() {
    }

    public static String translate(String raw) {
        if (raw == null || raw.isBlank()) return null;

        StringBuilder out = new StringBuilder();
        StringBuilder token = new StringBuilder();
        int depth = 0;
        for (char c : raw.trim().toCharArray()) {
            switch (c) {
                case '、', '，', ',', '/', ';', '；', ' ' -> {
                    flush(token, out);
                    appendSeparator(out);
                }
                case '（', '(' -> {
                    flush(token, out);
                    out.append('(');
                    depth++;
                }
                case '）', ')' -> {
                    flush(token, out);
                    trimTrailingSeparator(out);
                    if (depth > 0) {
                        out.append(')');
                        depth--;
                    }
                }
                default -> token.append(c);
            }
        }
        flush(token, out);
        trimTrailingSeparator(out);
        // Some source labels are truncated (e.g. "形、代、（动、数、"): close open groups.
        if (out.length() > 0 && out.charAt(out.length() - 1) == '(') out.setLength(out.length() - 1);
        trimTrailingSeparator(out);
        while (depth-- > 0 && out.indexOf("(") >= 0) out.append(')');
        return out.length() == 0 ? null : out.toString();
    }

    private static void flush(StringBuilder token, StringBuilder out) {
        if (token.length() == 0) return;
        String t = token.toString();
        token.setLength(0);
        String whole = TAGS.get(t);
        if (whole != null) {
            out.append(whole);
            return;
        }
        // Unknown compound (e.g. "动名"): split greedily into known tags, else keep as-is.
        StringBuilder parts = new StringBuilder();
        int i = 0;
        while (i < t.length()) {
            String match = null;
            for (String key : TAGS.keySet()) {
                if (t.startsWith(key, i)) {
                    match = key;
                    break;
                }
            }
            if (match == null) {
                out.append(t);
                return;
            }
            if (parts.length() > 0) parts.append(", ");
            parts.append(TAGS.get(match));
            i += match.length();
        }
        out.append(parts);
    }

    private static void appendSeparator(StringBuilder out) {
        if (out.length() == 0) return;
        char last = out.charAt(out.length() - 1);
        if (last == '(' || last == ' ') return;
        out.append(", ");
    }

    private static void trimTrailingSeparator(StringBuilder out) {
        int len = out.length();
        if (len >= 2 && out.charAt(len - 2) == ',' && out.charAt(len - 1) == ' ') out.setLength(len - 2);
    }
}
