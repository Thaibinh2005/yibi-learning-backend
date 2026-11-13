package com.yibi.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fills hanzi_syllables with every pinyin reading of the HSK writing characters (书写字),
 * split into initial, final and tone. Safe to run again: existing rows are kept.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HanziSyllableImportService {

    private final JdbcTemplate jdbc;

    public record Result(int characters, int readings, int skipped) {}

    @Transactional
    public Result run() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT hanzi, COALESCE(level_code, '1-2') AS level_code
                FROM hsk_hanzi_records
                WHERE source_level_label LIKE '%书写字' AND char_length(hanzi) = 1
                """);
        int readings = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            String hanzi = (String) row.get("hanzi");
            String level = (String) row.get("level_code");
            Set<String> syllables = dictionary(hanzi);
            if (syllables.isEmpty()) {
                skipped++;
                continue;
            }
            for (String numbered : syllables) {
                PinyinSyllable s;
                try {
                    s = PinyinSyllable.parse(numbered);
                } catch (IllegalArgumentException ex) {
                    log.warn("Skipping reading '{}' of {}: {}", numbered, hanzi, ex.getMessage());
                    continue;
                }
                readings += jdbc.update("""
                        INSERT INTO hanzi_syllables (hanzi, pinyin, initial, final_sound, tone, level_code)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (hanzi, pinyin) DO NOTHING
                        """, hanzi, s.marked(), s.initial(), s.finalSound(), s.tone(), level);
            }
        }
        log.info("Hanzi syllables: {} characters, {} new readings, {} without a reading.", rows.size(), readings, skipped);
        return new Result(rows.size(), readings, skipped);
    }

    /** Numbered readings such as "ni3" for a single character; empty when pinyin4j does not know it. */
    private static Set<String> dictionary(String hanzi) {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setToneType(HanyuPinyinToneType.WITH_TONE_NUMBER);
        format.setVCharType(HanyuPinyinVCharType.WITH_U_UNICODE);
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        Set<String> out = new LinkedHashSet<>();
        try {
            String[] found = PinyinHelper.toHanyuPinyinStringArray(hanzi.charAt(0), format);
            if (found != null) {
                out.addAll(List.of(found));
            }
        } catch (BadHanyuPinyinOutputFormatCombination ex) {
            throw new IllegalStateException("Invalid pinyin4j output format", ex);
        }
        return out;
    }
}
