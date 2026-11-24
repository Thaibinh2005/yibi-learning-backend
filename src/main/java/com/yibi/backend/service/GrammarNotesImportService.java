package com.yibi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Loads the grammar notes of data/grammar_notes_*.json into grammar_notes. A note names its syllabus point by
 * level, category, category name, subcategory and content, and is written to every record that matches (the
 * syllabus is stored twice). Safe to run again: a note is replaced by its newer version.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrammarNotesImportService {

    private static final String PATTERN = "classpath*:data/grammar_notes_*.json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public record Result(int notes, int records, int unmatched) {}

    @Transactional
    public Result run() {
        int notes = 0;
        int records = 0;
        int unmatched = 0;
        try {
            for (Resource file : new PathMatchingResourcePatternResolver().getResources(PATTERN)) {
                try (InputStream in = file.getInputStream()) {
                    JsonNode root = objectMapper.readTree(in);
                    String level = root.path("level").asText();
                    for (JsonNode note : root.path("notes")) {
                        notes++;
                        int written = write(level, note);
                        records += written;
                        if (written == 0) {
                            unmatched++;
                            log.warn("Grammar note matches no syllabus point: level {} '{}'", level, note.path("content").asText());
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read the grammar notes", ex);
        }
        log.info("Grammar notes: {} notes, {} records written, {} without a matching point.", notes, records, unmatched);
        return new Result(notes, records, unmatched);
    }

    private int write(String level, JsonNode note) throws IOException {
        List<Long> ids = jdbc.queryForList("""
                SELECT raw_record_id FROM hsk_grammar_records
                WHERE level_code = ? AND COALESCE(category, '') = ? AND COALESCE(category_name, '') = ?
                  AND COALESCE(subcategory, '') = ? AND COALESCE(grammar_content, '') = ?
                """, Long.class, level, note.path("category").asText(), note.path("categoryName").asText(),
                note.path("subcategory").asText(""), note.path("content").asText());
        String title = require(note, "title");
        String explanation = require(note, "explanation");
        String examples = objectMapper.writeValueAsString(note.path("examples"));
        String status = note.path("status").asText("DRAFT");
        for (Long id : ids) {
            jdbc.update("""
                    INSERT INTO grammar_notes (raw_record_id, title_vi, structure, explanation_vi, examples, status)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (raw_record_id) DO UPDATE SET title_vi = EXCLUDED.title_vi,
                        structure = EXCLUDED.structure, explanation_vi = EXCLUDED.explanation_vi,
                        examples = EXCLUDED.examples, status = EXCLUDED.status, updated_at = now()
                    """, id, title, note.path("structure").asText(null), explanation, examples, status);
        }
        return ids.size();
    }

    private static String require(JsonNode note, String field) {
        String value = note.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("Grammar note '" + note.path("content").asText() + "' has no " + field);
        }
        return value;
    }
}
