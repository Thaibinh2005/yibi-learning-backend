package com.yibi.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies reviewed corrections to the prepared questions of the learning path at startup.
 *
 * <p>The path is built once from data/hsk_path_2025.json, so editing that file never reaches a database that is
 * already built. Corrections go to data/path_question_fixes.json instead (made from the reviewed report of
 * audit_path_questions.py) and are applied here on every start: a question still worded as {@code match} is
 * replaced, or removed when {@code action} is "remove". Once applied the old wording is gone, so running again
 * changes nothing. Runs after {@link PathBuilderService}, so a fresh database gets the corrections too.
 */
@Slf4j
@Service
@Order(200)
@RequiredArgsConstructor
public class PathContentFixService implements ApplicationRunner {

    static final String RESOURCE = "data/path_question_fixes.json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> fixes = load();
        if (fixes.isEmpty()) return;
        int changed = 0;
        for (Map<String, Object> fix : fixes) {
            changed += apply(fix);
        }
        if (changed > 0) log.info("Path question fixes: {} question(s) corrected or removed", changed);
    }

    private int apply(Map<String, Object> fix) {
        String match = String.valueOf(fix.getOrDefault("match", "")).trim();
        if (match.isEmpty() || !(fix.get("level") instanceof Number level)) return 0;
        boolean remove = "remove".equals(fix.get("action"));
        List<Map<String, Object>> nodes = jdbc.queryForList("""
                SELECT n.id, n.content::text AS content FROM path_nodes n JOIN path_units u ON u.id = n.unit_id
                WHERE u.hsk_level = ? AND EXISTS (
                    SELECT 1 FROM jsonb_array_elements(n.content->'questions') q WHERE trim(q->>'question') = ?)
                """, level.intValue(), match);
        int changed = 0;
        for (Map<String, Object> node : nodes) {
            Map<String, Object> content = parse(String.valueOf(node.get("content")));
            List<Map<String, Object>> questions = new ArrayList<>();
            for (Map<String, Object> question : list(content.get("questions"))) {
                if (!match.equals(String.valueOf(question.get("question")).trim())) {
                    questions.add(question);
                    continue;
                }
                changed++;
                if (!remove) questions.add(corrected(question, fix));
            }
            content.put("questions", questions);
            jdbc.update("UPDATE path_nodes SET content = ?::jsonb WHERE id = ?", write(content), node.get("id"));
        }
        return changed;
    }

    /** The question with the fields the fix gives; fields it leaves out keep their old value. */
    private static Map<String, Object> corrected(Map<String, Object> question, Map<String, Object> fix) {
        Map<String, Object> result = new LinkedHashMap<>(question);
        for (String field : List.of("question", "options", "answer", "explanation")) {
            Object value = fix.get(field);
            if (value != null && !(value instanceof String text && text.isBlank())) result.put(field, value);
        }
        return result;
    }

    private List<Map<String, Object>> load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) return List.of();
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<>() { });
        } catch (IOException ex) {
            log.warn("Cannot read {}: {}", RESOURCE, ex.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new IllegalStateException("Bad path node content", ex);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }
}
