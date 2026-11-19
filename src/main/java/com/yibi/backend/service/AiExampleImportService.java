package com.yibi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the reviewed AI example sentences bundled in data/hsk_ai_examples.json into hsk_vocabulary_examples at
 * startup, so every contributor's database has the same sentences without calling Gemini (the file is made with
 * export_examples.py --bundle). A sentence already there for the word is skipped, so running again adds nothing.
 * A word whose syllabus record is missing in this database is skipped too.
 */
@Slf4j
@Service
@Order(150)
@RequiredArgsConstructor
public class AiExampleImportService implements ApplicationRunner {

    static final String RESOURCE = "data/hsk_ai_examples.json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) return;
        JsonNode examples;
        try (InputStream in = resource.getInputStream()) {
            examples = objectMapper.readTree(in).path("examples");
        } catch (IOException ex) {
            log.warn("Cannot read {}: {}", RESOURCE, ex.getMessage());
            return;
        }
        if (!examples.isArray() || examples.isEmpty()) return;
        int added = jdbc.update("""
                INSERT INTO hsk_vocabulary_examples (raw_record_id, sentence_zh, sentence_pinyin, translation_vi, source)
                SELECT r.raw_record_id, x.zh, NULLIF(x.py, ''), x.vi, COALESCE(x.src, 'ai_generated')
                FROM jsonb_to_recordset(?::jsonb) AS x(w text, l text, zh text, py text, vi text, src text)
                CROSS JOIN LATERAL (
                    SELECT v.raw_record_id FROM hsk_vocabulary_records v
                    WHERE regexp_replace(v.simplified, '[0-9]+$', '') = x.w AND v.level_code IS NOT DISTINCT FROM x.l
                    ORDER BY v.raw_record_id LIMIT 1) r
                WHERE NOT EXISTS (
                    SELECT 1 FROM hsk_vocabulary_examples e
                    JOIN hsk_vocabulary_records ev ON ev.raw_record_id = e.raw_record_id
                    WHERE regexp_replace(ev.simplified, '[0-9]+$', '') = x.w AND e.sentence_zh = x.zh)
                """, examples.toString());
        if (added > 0) log.info("AI example sentences: {} added from {}", added, RESOURCE);
    }
}
