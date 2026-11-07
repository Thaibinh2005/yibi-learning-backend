package com.yibi.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Builds the learning path of each level once, at startup, from the curriculum lessons and the themed units in
 * data/hsk_path_2025.json. A level that already has units, or has no lessons yet, is left alone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PathBuilderService implements ApplicationRunner {

    private static final String RESOURCE = "data/hsk_path_2025.json";
    private static final Map<String, Integer> LEVELS = new LinkedHashMap<>();
    private static final int WORDS_PER_LESSON = 5;
    private static final int LINES_PER_LESSON = 2;
    private static final int WORDS_PER_FALLBACK_UNIT = 20;

    static {
        List.of("1", "2", "3", "4", "5", "6").forEach(code -> LEVELS.put(code, Integer.parseInt(code)));
        LEVELS.put("7-9", 7);
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Map<String, Map<String, Object>> document = loadDocument();
        LEVELS.forEach((code, level) -> {
            Integer existing = jdbc.queryForObject("SELECT count(*) FROM path_units WHERE hsk_level = ?", Integer.class, level);
            if (existing != null && existing > 0) return;
            buildLevel(code, level, document.getOrDefault(code, Map.of()));
        });
    }

    private void buildLevel(String code, int level, Map<String, Object> planned) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT l.id, l.content_type, l.title, l.lesson_payload->>'sections' AS sections
                FROM lessons l JOIN courses c ON c.id = l.course_id
                WHERE c.hsk_level = ? ORDER BY l.lesson_order
                """, (rs, i) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("id", rs.getLong(1));
            row.put("type", rs.getString(2));
            row.put("title", rs.getString(3));
            row.put("sections", parseMap(rs.getString(4)));
            return row;
        }, level);
        if (rows.isEmpty()) return;

        Map<String, Map<String, Object>> words = new LinkedHashMap<>();
        List<Map<String, Object>> grammar = new ArrayList<>();
        List<Map<String, Object>> passages = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> sections = map(row.get("sections"));
            switch (String.valueOf(row.get("type"))) {
                case "VOCABULARY" -> list(sections.get("vocabulary")).forEach(word -> words.putIfAbsent(String.valueOf(word.get("simplified")), word));
                case "GRAMMAR" -> list(sections.get("grammar")).forEach(point -> {
                    // Sentence-type points are named only by their subcategory.
                    point.putIfAbsent("content", point.getOrDefault("subcategory", point.get("category_name")));
                    grammar.add(point);
                });
                case "READING", "LISTENING" -> passages.add(row);
                default -> { }
            }
        }
        attachRecordIds(words, level);
        // The syllabus tells homographs apart with a trailing number (和1, 和2); a learner should only see the word.
        words.values().forEach(word -> word.put("simplified", String.valueOf(word.get("simplified")).replaceAll("\\d+$", "")));

        List<Map<String, Object>> units = plannedUnits(planned, words.keySet());
        List<Map<String, Object>> bankGrammar = bankGrammarQuestions(code);
        Set<String> seenCharacters = new HashSet<>();
        Map<Integer, List<Map<String, Object>>> passagesByUnit = spreadPassages(passages, units.size());
        int nodes = 0;
        for (int index = 0; index < units.size(); index++) {
            Map<String, Object> unit = units.get(index);
            Long unitId = jdbc.queryForObject("""
                    INSERT INTO path_units (hsk_level, unit_order, title_vi, title_zh, icon) VALUES (?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, level, index + 1, unit.get("title_vi"), unit.get("title_zh"), unit.get("icon"));
            int order = 1;

            List<Map<String, Object>> unitWords = strings(unit.get("words")).stream().map(words::get).filter(Objects::nonNull).toList();
            List<Map<String, Object>> characters = newCharacters(unitWords, seenCharacters);
            List<Map<String, Object>> points = list(unit.get("grammar"));
            if (points.isEmpty()) {
                points = grammar.subList(index * grammar.size() / units.size(), (index + 1) * grammar.size() / units.size());
            }
            List<Map<String, Object>> questions = list(unit.get("grammar_questions"));
            if (!points.isEmpty() && questions.isEmpty() && !bankGrammar.isEmpty()) {
                questions = List.of(bankGrammar.get(index % bankGrammar.size()), bankGrammar.get((index + 1) % bankGrammar.size()));
            }
            Map<String, Object> dialogue = map(unit.get("dialogue"));
            List<Map<String, Object>> lines = list(dialogue.get("lines"));
            Set<Integer> usedLines = new HashSet<>();
            int lessons = Math.round(unitWords.size() / (float) WORDS_PER_LESSON);
            if (lessons == 0 && !(unitWords.isEmpty() && points.isEmpty())) lessons = 1;
            for (int part = 0; part < lessons; part++) {
                List<Map<String, Object>> chunk = share(unitWords, part, lessons);
                Set<String> chunkWords = new HashSet<>();
                chunk.forEach(word -> chunkWords.add(String.valueOf(word.get("simplified"))));
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("words", chunk);
                content.put("characters", characters.stream().filter(item -> chunkWords.contains(String.valueOf(item.get("word")))).toList());
                content.put("points", share(points, part, lessons));
                content.put("questions", share(questions, part, lessons));
                content.put("lines", linesUsing(chunkWords, lines, usedLines));
                insertNode(unitId, order++, "LESSON", "Bài " + (part + 1), content, null);
            }

            if (!dialogue.isEmpty()) {
                insertNode(unitId, order++, "DIALOGUE", "Hội thoại: " + dialogue.get("title"), dialogue, null);
            }

            for (Map<String, Object> passage : passagesByUnit.getOrDefault(index, List.of())) {
                Map<String, Object> sections = map(passage.get("sections"));
                boolean listening = "LISTENING".equals(passage.get("type"));
                List<Map<String, Object>> body = list(sections.get(listening ? "listenings" : "readings"));
                if (body.isEmpty()) continue;
                Map<String, Object> content = new LinkedHashMap<>(body.get(0));
                content.put("questions", list(sections.get("questions")));
                String title = (listening ? "Bài nghe: " : "Bài đọc: ") + content.getOrDefault("title", passage.get("title"));
                insertNode(unitId, order++, String.valueOf(passage.get("type")), title, content, (Long) passage.get("id"));
            }

            insertNode(unitId, order++, "CHECKPOINT", "Kiểm tra đơn vị", Map.of(), null);
            nodes += order - 1;
        }
        log.info("Learning path HSK {}: {} units, {} nodes.", code, units.size(), nodes);
    }

    /** One of several even parts of a list. */
    private static List<Map<String, Object>> share(List<Map<String, Object>> items, int part, int parts) {
        return items.subList(part * items.size() / parts, (part + 1) * items.size() / parts);
    }

    /** Dialogue lines a lesson can have read aloud: not yet used, and with one of its new words in them. */
    private static List<Map<String, Object>> linesUsing(Set<String> words, List<Map<String, Object>> lines, Set<Integer> used) {
        List<Map<String, Object>> chosen = new ArrayList<>();
        for (int i = 0; i < lines.size() && chosen.size() < LINES_PER_LESSON; i++) {
            String zh = String.valueOf(lines.get(i).get("zh"));
            if (!used.contains(i) && words.stream().anyMatch(zh::contains)) {
                used.add(i);
                chosen.add(lines.get(i));
            }
        }
        return chosen;
    }

    /** The themed units from the file; if a level has none yet, plain chunks so the path still works. */
    private List<Map<String, Object>> plannedUnits(Map<String, Object> planned, Set<String> known) {
        List<Map<String, Object>> units = list(planned.get("units"));
        if (!units.isEmpty()) return units;
        List<String> all = new ArrayList<>(known);
        List<Map<String, Object>> fallback = new ArrayList<>();
        for (int start = 0; start < all.size(); start += WORDS_PER_FALLBACK_UNIT) {
            Map<String, Object> unit = new HashMap<>();
            unit.put("title_vi", "Đơn vị " + (fallback.size() + 1));
            unit.put("title_zh", null);
            unit.put("icon", "📘");
            unit.put("words", all.subList(start, Math.min(start + WORDS_PER_FALLBACK_UNIT, all.size())));
            fallback.add(unit);
        }
        return fallback;
    }

    /** Passages are few, so they spread evenly over the level, each after the words it can draw on. */
    private Map<Integer, List<Map<String, Object>>> spreadPassages(List<Map<String, Object>> passages, int unitCount) {
        Map<Integer, List<Map<String, Object>>> byUnit = new HashMap<>();
        if (unitCount == 0) return byUnit;
        Map<String, List<Map<String, Object>>> byType = new TreeMap<>();
        passages.forEach(passage -> byType.computeIfAbsent(String.valueOf(passage.get("type")), key -> new ArrayList<>()).add(passage));
        // Reading and listening use the same passages, so they sit half a step apart rather than in one unit.
        byType.forEach((type, typed) -> {
            for (int i = 0; i < typed.size(); i++) {
                int unit = "LISTENING".equals(type)
                        ? (2 * i + 1) * unitCount / (2 * typed.size())
                        : (i + 1) * unitCount / typed.size() - 1;
                byUnit.computeIfAbsent(Math.min(unitCount - 1, unit), key -> new ArrayList<>()).add(typed.get(i));
            }
        });
        return byUnit;
    }

    /** Characters met for the first time on this level, each with the word it was met in as the clue. */
    private List<Map<String, Object>> newCharacters(List<Map<String, Object>> words, Set<String> seen) {
        List<Map<String, Object>> characters = new ArrayList<>();
        for (Map<String, Object> word : words) {
            String simplified = String.valueOf(word.get("simplified"));
            simplified.codePoints().filter(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN)
                    .mapToObj(Character::toString).forEach(hanzi -> {
                        if (seen.add(hanzi)) {
                            Map<String, Object> item = new HashMap<>();
                            item.put("hanzi", hanzi);
                            item.put("word", simplified);
                            item.put("pinyin", word.get("pinyin"));
                            item.put("meaning_vi", word.get("meaning_vi"));
                            characters.add(item);
                        }
                    });
        }
        return characters;
    }

    /** Links each word to its source record, which flashcards and pronunciation use. */
    private void attachRecordIds(Map<String, Map<String, Object>> words, int level) {
        jdbc.query("""
                SELECT v.simplified, min(v.raw_record_id), max(v.audio_url)
                FROM lesson_content_items i
                JOIN lessons l ON l.id = i.lesson_id JOIN courses c ON c.id = l.course_id
                JOIN hsk_vocabulary_records v ON v.raw_record_id = i.raw_record_id
                WHERE c.hsk_level = ? AND i.content_type = 'VOCABULARY'
                GROUP BY v.simplified
                """, rs -> {
            Map<String, Object> word = words.get(rs.getString(1));
            if (word != null) {
                word.put("raw_record_id", rs.getLong(2));
                word.put("audio_url", rs.getString(3));
            }
        }, level);
    }

    /** Published grammar questions, for units without questions of their own. */
    private List<Map<String, Object>> bankGrammarQuestions(String code) {
        return jdbc.query("""
                SELECT q.prompt, q.answer_text, q.explanation,
                       (SELECT array_agg(choice_text ORDER BY choice_order) FROM question_bank_choices c WHERE c.question_id = q.id)
                FROM question_bank_items q
                WHERE q.status = 'PUBLISHED' AND q.level_code = ? AND q.skill_key = 'grammar' AND q.question_type = 'GRAMMAR_SELECT'
                ORDER BY q.id
                """, (rs, i) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("question", rs.getString(1));
            item.put("answer", rs.getString(2));
            item.put("explanation", rs.getString(3));
            java.sql.Array options = rs.getArray(4);
            item.put("options", options == null ? List.of() : List.of((Object[]) options.getArray()));
            return item;
        }, code);
    }

    private void insertNode(Long unitId, int order, String type, String title, Map<String, Object> content, Long lessonId) {
        try {
            jdbc.update("""
                    INSERT INTO path_nodes (unit_id, node_order, node_type, title, content, source_lesson_id)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?)
                    """, unitId, order, type, title, objectMapper.writeValueAsString(content), lessonId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot store path node " + title, ex);
        }
    }

    private Map<String, Map<String, Object>> loadDocument() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) return Map.of();
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + RESOURCE, ex);
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new IllegalStateException("Bad lesson sections", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> items ? items.stream().map(String::valueOf).toList() : List.of();
    }
}
