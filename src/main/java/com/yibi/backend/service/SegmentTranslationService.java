package com.yibi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vietnamese translations of Chinese sentences by Gemini, cached by sentence in segment_translations so the same
 * sentence is translated once for every learner. Sentences go in numbered batches with their neighbours as context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentTranslationService {

    public static final int BATCH = 30;

    private final JdbcTemplate jdbc;
    private final GeminiJsonClient gemini;

    public boolean available() {
        return gemini.configured();
    }

    /** Translations already in the cache, by sentence. */
    public Map<String, String> cached(List<String> texts) {
        Map<String, String> byHash = new HashMap<>();
        List<String> hashes = texts.stream().map(SegmentTranslationService::hash).distinct().toList();
        for (int from = 0; from < hashes.size(); from += 500) {
            List<String> part = hashes.subList(from, Math.min(hashes.size(), from + 500));
            String marks = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            jdbc.query("SELECT text_hash, vi FROM segment_translations WHERE text_hash IN (" + marks + ")",
                    rs -> { byHash.put(rs.getString(1).trim(), rs.getString(2)); }, part.toArray());
        }
        Map<String, String> result = new HashMap<>();
        for (String text : texts) {
            String vi = byHash.get(hash(text));
            if (vi != null) result.put(text, vi);
        }
        return result;
    }

    /**
     * Translates one batch (at most {@link #BATCH} distinct sentences) and stores the results. {@code context} holds the
     * whole transcript in order, so each sentence is read with the lines around it.
     *
     * @return the translations Gemini returned in a usable form; a sentence it skipped is simply missing
     */
    public Map<String, String> translateBatch(String title, List<String> batch, List<String> context) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("items", Map.of("type", "array", "items", Map.of(
                        "type", "object",
                        "properties", Map.of("i", Map.of("type", "integer"), "vi", Map.of("type", "string")),
                        "required", List.of("i", "vi")))),
                "required", List.of("items"));
        JsonNode answer = gemini.generate(prompt(title, batch, context), schema, 0.2);
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode item : answer.path("items")) {
            int i = item.path("i").asInt(-1);
            String vi = item.path("vi").asText("").trim();
            if (i < 1 || i > batch.size() || !usable(vi)) continue;
            result.put(batch.get(i - 1), vi);
        }
        for (Map.Entry<String, String> e : result.entrySet()) {
            jdbc.update("""
                    INSERT INTO segment_translations (text_hash, source_text, vi) VALUES (?, ?, ?)
                    ON CONFLICT (text_hash) DO NOTHING
                    """, hash(e.getKey()), e.getKey(), e.getValue());
        }
        return result;
    }

    static String prompt(String title, List<String> batch, List<String> context) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) lines.append(i + 1).append(". ").append(batch.get(i)).append('\n');
        String before = "";
        String after = "";
        if (!batch.isEmpty() && context != null) {
            int first = context.indexOf(batch.get(0));
            int last = context.lastIndexOf(batch.get(batch.size() - 1));
            if (first > 0) before = context.get(first - 1);
            if (last >= 0 && last + 1 < context.size()) after = context.get(last + 1);
        }
        return """
                Bạn là biên dịch viên Trung - Việt cho người học tiếng Trung.
                Dịch từng câu phụ đề tiếng Trung dưới đây sang tiếng Việt tự nhiên, theo văn nói.
                Quy tắc:
                - Mỗi câu một bản dịch, giữ đúng số thứ tự "i" của câu.
                - Không thêm hay bỏ ý; không giải thích; không chép lại chữ Hán.
                - Tên người, địa danh ghi bằng pinyin không dấu thanh hoặc tên tiếng Việt quen dùng.
                - Câu bị cắt dở thì dịch phần đang có, đọc câu trước/sau để hiểu nghĩa.
                Tiêu đề video: %s
                Câu ngay trước (chỉ để hiểu ngữ cảnh, không dịch): %s
                Câu ngay sau (chỉ để hiểu ngữ cảnh, không dịch): %s
                Các câu cần dịch:
                %s""".formatted(title == null ? "" : title, before, after, lines);
    }

    /** A translation is unusable when it is empty or still holds Chinese characters. */
    static boolean usable(String vi) {
        return !vi.isBlank() && vi.codePoints().noneMatch(ChinesePinyinService::isHan);
    }

    public static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Splits the sentences into batches of distinct sentences, in order. */
    public static List<List<String>> batches(List<String> texts) {
        List<String> distinct = new ArrayList<>(new java.util.LinkedHashSet<>(texts));
        List<List<String>> out = new ArrayList<>();
        for (int from = 0; from < distinct.size(); from += BATCH) {
            out.add(distinct.subList(from, Math.min(distinct.size(), from + BATCH)));
        }
        return out;
    }
}
