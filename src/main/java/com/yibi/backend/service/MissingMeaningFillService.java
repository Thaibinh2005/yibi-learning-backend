package com.yibi.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives a Vietnamese meaning and a pinyin to every syllabus record that came without one. The syllabus lists most
 * words several times, and only some copies carry senses: 感冒 (HSK 3) has eight records, four of them empty, and a
 * flashcard, topic or lesson that points at an empty copy showed "Chưa có nghĩa tiếng Việt". About ninety words
 * (给 at HSK 2, 点 at HSK 3 and 4, 没（有）, 有时（候）...) have no senses in any copy of their level. Each empty
 * record gets the Vietnamese senses, and a reading if it has none, of another record of the same word (see DONORS).
 * Runs at startup before the path is built; a filled record is no longer empty, so running again adds nothing.
 */
@Slf4j
@Service
@Order(90)
@RequiredArgsConstructor
public class MissingMeaningFillService implements ApplicationRunner {

    /**
     * Each record without a Vietnamese sense, with the record of the same word it takes them from: one of the same
     * level first (the syllabus lists most words several times and only some copies carry senses), then the word
     * with its bracketed part kept (没有) or dropped (没), then the lowest level; among those the one with the
     * fewest senses, as that one keeps the senses joined in one line.
     */
    private static final String DONORS = """
            WITH rec AS MATERIALIZED (
                SELECT v.raw_record_id, regexp_replace(v.simplified, '[0-9]+$', '') AS word, v.level_code,
                       (SELECT count(*) FROM hsk_vocabulary_senses s
                        WHERE s.raw_record_id = v.raw_record_id AND lower(s.language_code) = 'vi' AND btrim(s.meaning) <> '') AS vi
                FROM hsk_vocabulary_records v
            ),
            wanted AS (
                SELECT raw_record_id, level_code, word AS k, 0 AS variant FROM rec WHERE vi = 0
                UNION ALL
                SELECT raw_record_id, level_code, regexp_replace(word, '（([^）]*)）', '\1', 'g'), 1 FROM rec WHERE vi = 0 AND word LIKE '%（%'
                UNION ALL
                SELECT raw_record_id, level_code, regexp_replace(word, '（[^）]*）', '', 'g'), 1 FROM rec WHERE vi = 0 AND word LIKE '%（%'
            ),
            donor AS (
                SELECT DISTINCT ON (w.raw_record_id) w.raw_record_id, d.raw_record_id AS donor_id
                FROM wanted w JOIN rec d ON d.word = w.k AND d.vi > 0
                ORDER BY w.raw_record_id, (d.level_code IS NOT DISTINCT FROM w.level_code) DESC, w.variant,
                         d.level_code, d.vi, d.raw_record_id
            )
            """;

    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Readings first: once the senses are in, the words no longer count as missing.
        int readings = jdbc.update(DONORS + """
                INSERT INTO hsk_vocabulary_readings (raw_record_id, reading_order, pinyin, zhuyin)
                SELECT d.raw_record_id, r.reading_order, r.pinyin, r.zhuyin
                FROM donor d JOIN hsk_vocabulary_readings r ON r.raw_record_id = d.donor_id
                WHERE NOT EXISTS (SELECT 1 FROM hsk_vocabulary_readings own WHERE own.raw_record_id = d.raw_record_id)
                """);
        int senses = jdbc.update(DONORS + """
                INSERT INTO hsk_vocabulary_senses (raw_record_id, sense_order, meaning, language_code, part_of_speech)
                -- Numbered after the record's own senses in other languages (English for a few).
                SELECT d.raw_record_id,
                       COALESCE((SELECT max(own.sense_order) FROM hsk_vocabulary_senses own WHERE own.raw_record_id = d.raw_record_id), 0)
                           + s.sense_order,
                       s.meaning, s.language_code, s.part_of_speech
                FROM donor d JOIN hsk_vocabulary_senses s ON s.raw_record_id = d.donor_id
                WHERE lower(s.language_code) = 'vi' AND btrim(s.meaning) <> ''
                """);
        if (senses > 0 || readings > 0) {
            log.info("Words without a Vietnamese meaning: {} senses and {} readings copied from the same word", senses, readings);
        }
    }
}
