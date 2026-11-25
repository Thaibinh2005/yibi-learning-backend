package com.yibi.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.dto.grammar.GrammarDtos.Example;
import com.yibi.backend.dto.grammar.GrammarDtos.Note;
import com.yibi.backend.dto.grammar.GrammarDtos.Point;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** The grammar points of a library lesson, each with its Vietnamese note where one has been written. */
@Service
@RequiredArgsConstructor
public class GrammarNotesService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<Point> forLesson(Long lessonId) {
        return jdbc.query("""
                SELECT g.raw_record_id, g.grammar_content, g.category, g.category_name, g.subcategory,
                       n.title_vi, n.structure, n.explanation_vi, n.examples::text AS examples, n.status
                FROM lesson_content_items i
                JOIN hsk_grammar_records g ON g.raw_record_id = i.raw_record_id
                LEFT JOIN grammar_notes n ON n.raw_record_id = g.raw_record_id
                WHERE i.lesson_id = ? AND i.content_type = 'GRAMMAR'
                ORDER BY i.item_order, i.id
                """, this::mapRow, lessonId);
    }

    private Point mapRow(ResultSet rs, int row) throws SQLException {
        Note note = rs.getString("title_vi") == null ? null : new Note(
                rs.getString("title_vi"), rs.getString("structure"), rs.getString("explanation_vi"),
                examples(rs.getString("examples")), rs.getString("status"));
        return new Point(rs.getLong("raw_record_id"), rs.getString("grammar_content"), rs.getString("category"),
                rs.getString("category_name"), rs.getString("subcategory"), note);
    }

    private List<Example> examples(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, new TypeReference<>() {});
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Stored grammar examples are not valid JSON", ex);
        }
    }
}
