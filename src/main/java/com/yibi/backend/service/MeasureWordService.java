package com.yibi.backend.service;

import com.yibi.backend.dto.vocabulary.MeasureWordResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;

/**
 * Looks up measure words (量词) for nouns from measure_word_links
 * (built by scripts/extract_measure_words.py).
 */
@Service
@RequiredArgsConstructor
public class MeasureWordService {

    /** Show at most this many measure words per noun (CEDICT order = most common first). */
    static final int MAX_PER_NOUN = 3;

    private final JdbcTemplate jdbc;

    /** Strip homograph digits used by some sources: 会1 -> 会. */
    public static String cleanNoun(String hanzi) {
        return hanzi == null ? null : hanzi.replaceAll("[0-9]+$", "");
    }

    /**
     * Measure words for each noun.
     *
     * @param nounPinyin noun (as stored in hanzi, digits allowed) -> its pinyin; used for the example
     * @return cleaned noun -> measure words; nouns without measure words are absent
     */
    public Map<String, List<MeasureWordResponse>> findForNouns(Map<String, String> nounPinyin) {
        Map<String, String> pinyinByNoun = new HashMap<>();
        nounPinyin.forEach((hanzi, pinyin) -> {
            String noun = cleanNoun(hanzi);
            if (noun != null && !noun.isBlank()) pinyinByNoun.putIfAbsent(noun, pinyin);
        });
        if (pinyinByNoun.isEmpty()) return Map.of();

        List<String> nouns = new ArrayList<>(pinyinByNoun.keySet());
        String placeholders = String.join(",", Collections.nCopies(nouns.size(), "?"));
        String sql = """
            SELECT l.noun, l.measure_word, l.measure_word_traditional, l.measure_word_pinyin, sense.meaning
            FROM measure_word_links l
            LEFT JOIN LATERAL (
                -- Only the classifier sense ("lượng từ cho ..."); a word's first sense is
                -- often unrelated (本 = "gốc, rễ"), which would mislead learners.
                SELECT parts.seg AS meaning
                FROM hsk_vocabulary_senses s, LATERAL (SELECT btrim(t) AS seg FROM regexp_split_to_table(s.meaning, ';') t) parts
                WHERE s.raw_record_id = l.measure_word_record_id AND lower(s.language_code) = 'vi'
                  AND parts.seg ILIKE '%%lượng từ%%'
                ORDER BY s.sense_order LIMIT 1
            ) sense ON TRUE
            WHERE l.noun IN (%s)
            ORDER BY l.noun, l.sort_order, l.id
            """.formatted(placeholders);

        Map<String, List<MeasureWordResponse>> result = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            String noun = rs.getString("noun");
            List<MeasureWordResponse> list = result.computeIfAbsent(noun, k -> new ArrayList<>());
            if (list.size() >= MAX_PER_NOUN) return;
            String mw = rs.getString("measure_word");
            String mwPinyin = rs.getString("measure_word_pinyin");
            String nounPy = pinyinByNoun.get(noun);
            list.add(MeasureWordResponse.builder()
                    .measureWord(mw)
                    .measureWordTraditional(rs.getString("measure_word_traditional"))
                    .pinyin(mwPinyin)
                    .meaningVi(meaningOf(mw, rs.getString("meaning")))
                    .examplePhrase("一" + mw + noun)
                    .examplePinyin(yiWithSandhi(mwPinyin) + " " + mwPinyin
                            + (nounPy == null || nounPy.isBlank() ? "" : " " + nounPy.trim()))
                    .build());
        }, nouns.toArray());
        return result;
    }

    public List<MeasureWordResponse> findForNoun(String hanzi, String pinyin) {
        Map<String, String> one = new HashMap<>();
        one.put(hanzi, pinyin);
        return findForNouns(one).getOrDefault(cleanNoun(hanzi), List.of());
    }

    /**
     * Vietnamese classifier meanings for measure words whose dictionary senses have
     * no "lượng từ ..." entry (hand-written; keep short).
     */
    static final Map<String, String> CURATED_MEANINGS = Map.ofEntries(
            Map.entry("次", "lần (số lần xảy ra)"),
            Map.entry("片", "mảnh, miếng; vùng (vật mỏng, dẹt; khoảng rộng)"),
            Map.entry("部", "bộ (phim, sách, máy móc)"),
            Map.entry("本", "quyển, cuốn (sách, vở)"),
            Map.entry("道", "món (ăn); câu (hỏi); đường, vệt; lớp (cửa)"),
            Map.entry("台", "chiếc, cỗ (máy móc, thiết bị); vở (kịch)"),
            Map.entry("支", "cây, chiếc (vật dài nhỏ như bút); đội; bài (hát)"),
            Map.entry("门", "môn (học, kỹ thuật); khẩu (pháo)"),
            Map.entry("顿", "bữa (ăn); trận (mắng, đánh)"),
            Map.entry("套", "bộ (quần áo, sách, đồ dùng); căn (hộ)"),
            Map.entry("双", "đôi (vật đi thành cặp: giày, đũa)"),
            Map.entry("副", "cặp, bộ (kính, găng tay, bài)"),
            Map.entry("对", "đôi, cặp (người hoặc vật thành đôi)"),
            Map.entry("头", "con (gia súc lớn: bò, lợn); củ (tỏi)"),
            Map.entry("处", "chỗ, nơi (địa điểm)"),
            Map.entry("阵", "trận, cơn (gió, mưa, tiếng động)"),
            Map.entry("通", "cuộc (điện thoại); hồi (trống)"),
            Map.entry("股", "luồng, làn (khí, mùi, sức lực)"),
            Map.entry("盒", "hộp"),
            Map.entry("节", "tiết (học); toa (tàu); đốt"),
            Map.entry("宗", "vụ, khoản (việc, giao dịch)"),
            Map.entry("点", "điểm, ý (ý kiến, đề nghị)"),
            Map.entry("群", "đàn, bầy, nhóm"),
            Map.entry("罐", "lon, hũ"),
            Map.entry("顶", "chiếc (mũ, lều, màn)"),
            Map.entry("盘", "đĩa; ván (cờ); cuộn (băng)"),
            Map.entry("滴", "giọt"),
            Map.entry("起", "vụ (sự việc, tai nạn)"),
            Map.entry("幢", "tòa (nhà)"),
            Map.entry("身", "bộ (quần áo)"),
            Map.entry("层", "tầng, lớp"),
            Map.entry("管", "ống (vật dạng ống)"),
            Map.entry("桶", "thùng, xô"),
            Map.entry("碗", "bát"),
            Map.entry("撮", "nhúm, dúm"),
            Map.entry("瓣", "múi, tép, cánh (hoa)"),
            Map.entry("方", "chiếc (vật vuông: con dấu, khăn); khối (mét khối)"),
            Map.entry("堆", "đống"),
            Map.entry("匹", "con (ngựa); xấp (vải)"),
            Map.entry("丝", "sợi, tia, chút"),
            Map.entry("包", "gói, bao"),
            Map.entry("户", "hộ (gia đình)"),
            Map.entry("列", "đoàn, chuyến (tàu)"),
            Map.entry("簇", "chùm, khóm, cụm"),
            Map.entry("秒", "giây"),
            Map.entry("档", "loại, mức; tiết mục (chương trình)"),
            Map.entry("盆", "chậu"),
            Map.entry("沓", "xấp, tập (giấy)"),
            Map.entry("打", "tá (12 cái)"),
            Map.entry("具", "cỗ, chiếc (quan tài, thi thể, máy móc)"),
            Map.entry("波", "đợt, làn"),
            Map.entry("尾", "con (cá)"),
            Map.entry("页", "trang"),
            Map.entry("分", "phút; điểm; xu"),
            Map.entry("叶", "chiếc (thuyền nhỏ)"),
            Map.entry("记", "cú, cái (đòn đánh)"),
            Map.entry("泡", "bãi (nước tiểu, phân)"),
            Map.entry("曲", "khúc, bài (hát)"),
            Map.entry("样", "loại, thứ, món"),
            Map.entry("袋", "túi, bao"),
            Map.entry("回", "lần; hồi (chương truyện)"),
            Map.entry("尊", "pho (tượng); khẩu (pháo)")
    );

    /** Dictionary classifier sense if present, otherwise the curated meaning. */
    static String meaningOf(String measureWord, String classifierSense) {
        String fromDb = shortMeaning(classifierSense);
        return fromDb != null ? fromDb : CURATED_MEANINGS.get(measureWord);
    }

    /** Trim a sense for display: "quyển; cuốn" -> "quyển"; long text is cut at 90 chars. */
    static String shortMeaning(String meaning) {
        if (meaning == null || meaning.isBlank()) return null;
        String first = meaning.split("[;；]")[0].trim();
        return first.length() > 90 ? first.substring(0, 90).trim() + "…" : first;
    }

    /** 一 is yí before a 4th-tone syllable and yì before tones 1-3 (一个 yí gè, 一本 yì běn). */
    static String yiWithSandhi(String nextPinyin) {
        if (nextPinyin == null || nextPinyin.isBlank()) return "yī";
        String decomposed = Normalizer.normalize(nextPinyin.split("\s+")[0], Normalizer.Form.NFD);
        if (decomposed.indexOf('\u0300') >= 0) return "yí";   // grave accent = 4th tone
        if (decomposed.indexOf('\u0304') >= 0 || decomposed.indexOf('\u0301') >= 0
                || decomposed.indexOf('\u030C') >= 0) return "yì";
        return "yí"; // neutral tone (e.g. 个 ge) is underlyingly 4th tone
    }
}
