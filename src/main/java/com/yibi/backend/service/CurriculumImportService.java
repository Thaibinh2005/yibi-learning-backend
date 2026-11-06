package com.yibi.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports the syllabus grammar points and builds one course per HSK 3.0 level from the
 * vocabulary, grammar and hanzi rows already in the hsk_* tables. Safe to re-run: a course
 * or a lesson type that already exists is left alone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurriculumImportService {

    private static final String GRAMMAR_RESOURCE = "data/hsk_grammar_2025.json";
    private static final String GRAMMAR_DATASET_KEY = "hsk-grammar-2025-krmanik";
    private static final String MEANINGS_RESOURCE = "data/hsk_meanings_2025.json";
    private static final String MEANINGS_DATASET_KEY = "hsk-meanings-cvdict";
    private static final List<String> LEVELS = List.of("1", "2", "3", "4", "5", "6", "7-9");
    private static final int WORDS_PER_LESSON = 20;
    private static final int GRAMMAR_PER_LESSON = 10;
    private static final int HANZI_PER_LESSON = 20;

    private static final String EXAMPLES_RESOURCE = "data/hsk_examples_2025.json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Map<Integer, JsonNode> examplesBySeq = new java.util.HashMap<>();
    private final Map<Long, Map<String, Object>> examplesByRawId = new java.util.HashMap<>();

    public record Result(int grammarRows, int meanings, int courses, int lessons) {}

    @Transactional
    public Result run() {
        int grammarRows = importGrammar();
        int meanings = importMeanings();
        int courses = 0;
        int lessons = 0;
        for (String level : LEVELS) {
            Long existing = findCourse(level);
            long courseId = existing != null ? existing : createCourse(level);
            if (existing == null) courses++;
            lessons += buildLessons(courseId, level);
            jdbc.update("UPDATE courses SET total_lessons = (SELECT COUNT(*) FROM lessons WHERE course_id = ?) WHERE id = ?",
                    courseId, courseId);
        }
        loadExamples();
        if (meanings > 0 || !examplesBySeq.isEmpty()) refreshVocabularyPayloads();
        log.info("Curriculum: {} grammar rows, {} meanings, {} courses and {} lessons created.", grammarRows, meanings, courses, lessons);
        return new Result(grammarRows, meanings, courses, lessons);
    }

    /** Stores Vietnamese (or English fallback) meanings as unreviewed senses; words that already have senses are skipped. */
    private int importMeanings() {
        JsonNode doc = parse(readResource(MEANINGS_RESOURCE), MEANINGS_RESOURCE);
        jdbc.update("""
                INSERT INTO hsk_source_datasets
                  (dataset_key, standard_code, version_code, repository_url, license_name, license_notes, verification_status)
                VALUES (?, 'NEW_HSK', '2025', 'https://github.com/ph0ngp/CVDICT', 'CC BY-SA 4.0', ?, 'UNVERIFIED')
                ON CONFLICT (dataset_key) DO NOTHING
                """, MEANINGS_DATASET_KEY, doc.path("source").path("vi").asText() + ". " + doc.path("source").path("notes").asText());

        Map<Integer, Long> rawIdBySeq = new java.util.HashMap<>();
        jdbc.query("""
                SELECT r.source_row_number, r.id FROM hsk_raw_records r
                JOIN hsk_source_files f ON f.id = r.source_file_id
                WHERE f.relative_path = 'data/hsk_syllabus_2025.json' AND r.record_kind = 'VOCABULARY'
                """, rs -> { rawIdBySeq.put(rs.getInt(1), rs.getLong(2)); });
        java.util.Set<Long> done = new java.util.HashSet<>(
                jdbc.queryForList("SELECT DISTINCT raw_record_id FROM hsk_vocabulary_senses", Long.class));

        int imported = 0;
        for (Map.Entry<String, JsonNode> entry : doc.path("senses").properties()) {
            Long rawId = rawIdBySeq.get(Integer.parseInt(entry.getKey()));
            if (rawId == null || done.contains(rawId)) continue;
            boolean vietnamese = entry.getValue().has("vi");
            int order = 0;
            for (JsonNode gloss : entry.getValue().path(vietnamese ? "vi" : "en")) {
                long senseId = jdbc.queryForObject("""
                        INSERT INTO hsk_vocabulary_senses (raw_record_id, sense_order, meaning, language_code, verification_status)
                        VALUES (?, ?, ?, ?, 'NEEDS_REVIEW') RETURNING id
                        """, Long.class, rawId, ++order, gloss.asText(), vietnamese ? "vi" : "en");
                jdbc.update("""
                        INSERT INTO hsk_vocabulary_reading_senses (reading_id, sense_id)
                        SELECT id, ? FROM hsk_vocabulary_readings WHERE raw_record_id = ? AND reading_order = 1
                        """, senseId, rawId);
            }
            imported++;
        }
        return imported;
    }

    /** Rewrites the vocabulary section of existing vocabulary lessons so they pick up the new meanings. */
    private void refreshVocabularyPayloads() {
        for (Long lessonId : jdbc.queryForList("SELECT id FROM lessons WHERE content_type = 'VOCABULARY'", Long.class)) {
            List<Map<String, Object>> items = jdbc.query(VOCABULARY_SELECT + """
                    JOIN lesson_content_items i ON i.raw_record_id = v.raw_record_id AND i.lesson_id = ?
                    ORDER BY i.item_order
                    """, (rs, n) -> vocabularyItem(rs), lessonId);
            jdbc.update("UPDATE lessons SET lesson_payload = jsonb_set(lesson_payload, '{sections,vocabulary}', ?::jsonb) WHERE id = ?",
                    toJson(items.stream().map(CurriculumImportService::withoutRawId).toList()), lessonId);
        }
    }

    /** Tatoeba sentences (CC BY 2.0 FR) per syllabus seq, attached to each vocabulary item as "example". */
    private void loadExamples() {
        examplesByRawId.clear();
        examplesBySeq.clear();
        JsonNode doc = parse(readResource(EXAMPLES_RESOURCE), EXAMPLES_RESOURCE);
        jdbc.update("""
                INSERT INTO hsk_source_datasets
                  (dataset_key, standard_code, version_code, repository_url, license_name, license_notes, verification_status)
                VALUES ('hsk-examples-tatoeba', 'NEW_HSK', '2025', 'https://tatoeba.org', 'CC BY 2.0 FR', ?, 'UNVERIFIED')
                ON CONFLICT (dataset_key) DO NOTHING
                """, doc.path("source").path("notes").asText());
        doc.path("examples").properties().forEach(e -> examplesBySeq.put(Integer.parseInt(e.getKey()), e.getValue()));
        jdbc.query("""
                SELECT r.source_row_number, r.id FROM hsk_raw_records r
                JOIN hsk_source_files f ON f.id = r.source_file_id
                WHERE f.relative_path = 'data/hsk_syllabus_2025.json' AND r.record_kind = 'VOCABULARY'
                """, rs -> {
            JsonNode example = examplesBySeq.get(rs.getInt(1));
            if (example != null) {
                examplesByRawId.put(rs.getLong(2), Map.of("zh", example.path("zh").asText(),
                        "en", example.path("en").asText(), "tatoeba_id", example.path("id").asInt()));
            }
        });
    }

    private static final String VOCABULARY_SELECT = """
            SELECT v.raw_record_id, v.simplified, v.part_of_speech, r.pinyin,
              (SELECT string_agg(s.meaning, '; ' ORDER BY s.sense_order) FROM hsk_vocabulary_senses s
                WHERE s.raw_record_id = v.raw_record_id AND s.language_code = 'vi' AND s.sense_order <= 3) AS meaning_vi,
              (SELECT string_agg(s.meaning, '; ' ORDER BY s.sense_order) FROM hsk_vocabulary_senses s
                WHERE s.raw_record_id = v.raw_record_id AND s.language_code = 'en' AND s.sense_order <= 3) AS meaning_en
            FROM hsk_vocabulary_records v
            JOIN hsk_vocabulary_readings r ON r.raw_record_id = v.raw_record_id AND r.reading_order = 1
            """;

    private Map<String, Object> vocabularyItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> item = item(rs.getLong(1), "simplified", rs.getString(2), "pinyin", rs.getString(4),
                "part_of_speech", rs.getString(3), "meaning_vi", rs.getString(5), "meaning_en", rs.getString(6));
        Map<String, Object> example = examplesByRawId.get(rs.getLong(1));
        if (example != null) item.put("example", example);
        return item;
    }

    private static Map<String, Object> withoutRawId(Map<String, Object> source) {
        Map<String, Object> m = new LinkedHashMap<>(source);
        m.remove("raw_record_id");
        return m;
    }

    private int importGrammar() {
        byte[] bytes = readResource(GRAMMAR_RESOURCE);
        String sha = SyllabusImportService.sha256(bytes);
        JsonNode doc = parse(bytes, GRAMMAR_RESOURCE);

        jdbc.update("""
                INSERT INTO hsk_source_datasets
                  (dataset_key, standard_code, version_code, repository_url, license_name, license_notes, verification_status)
                VALUES (?, 'NEW_HSK', '2025', ?, 'Official public syllabus', ?, 'UNVERIFIED')
                ON CONFLICT (dataset_key) DO NOTHING
                """, GRAMMAR_DATASET_KEY, doc.path("source").path("origin").asText(),
                "Grammar points of the official syllabus, taken from the krmanik/HSK-3.0 repository, whose licence file is NOASSERTION.");
        long datasetId = jdbc.queryForObject("SELECT id FROM hsk_source_datasets WHERE dataset_key = ?", Long.class, GRAMMAR_DATASET_KEY);
        Boolean already = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM hsk_source_files WHERE dataset_id = ? AND sha256 = ?)",
                Boolean.class, datasetId, sha);
        if (Boolean.TRUE.equals(already)) return 0;

        int total = 0;
        for (JsonNode rows : doc.path("levels")) total += rows.size();
        long fileId = jdbc.queryForObject("""
                INSERT INTO hsk_source_files (dataset_id, relative_path, sha256, format, byte_size, detected_encoding, record_count)
                VALUES (?, ?, ?, 'JSON', ?, 'UTF-8', ?) RETURNING id
                """, Long.class, datasetId, GRAMMAR_RESOURCE, sha, bytes.length, total);

        int row = 0;
        for (String level : LEVELS) {
            for (JsonNode g : doc.path("levels").path(level)) {
                long rawId = jdbc.queryForObject("""
                        INSERT INTO hsk_raw_records (source_file_id, source_row_number, record_kind, raw_text, record_sha256)
                        VALUES (?, ?, 'GRAMMAR', ?, ?) RETURNING id
                        """, Long.class, fileId, ++row, g.toString(),
                        SyllabusImportService.sha256(g.toString().getBytes(StandardCharsets.UTF_8)));
                jdbc.update("""
                        INSERT INTO hsk_grammar_records
                          (raw_record_id, level_code, source_level_label, category, category_name, subcategory, grammar_content)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, rawId, level, "HSK " + level, blankToNull(g.path("category")),
                        blankToNull(g.path("categoryName")), blankToNull(g.path("subcategory")), blankToNull(g.path("content")));
            }
        }
        return total;
    }

    private Long findCourse(String level) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM courses WHERE hsk_level = ? AND title = ?",
                Long.class, levelNumber(level), courseTitle(level));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long createCourse(String level) {
        return jdbc.queryForObject("INSERT INTO courses (title, description, hsk_level) VALUES (?, ?, ?) RETURNING id",
                Long.class, courseTitle(level),
                "Từ vựng, ngữ pháp và chữ Hán của HSK " + levelLabel(level) + " theo đề cương mới.", levelNumber(level));
    }

    private int buildLessons(long courseId, String level) {
        int created = 0;
        int order = jdbc.queryForObject("SELECT COALESCE(MAX(lesson_order), 0) FROM lessons WHERE course_id = ?", Integer.class, courseId);

        if (!hasLessons(courseId, "VOCABULARY")) {
            List<Map<String, Object>> items = jdbc.query(VOCABULARY_SELECT + "WHERE v.level_code = ? ORDER BY v.raw_record_id",
                    (rs, i) -> vocabularyItem(rs), level);
            created += addLessons(courseId, level, order, "VOCABULARY", "vocabulary", "Từ vựng", items, WORDS_PER_LESSON);
            order += lessonCount(items.size(), WORDS_PER_LESSON);
        }
        if (!hasLessons(courseId, "GRAMMAR")) {
            List<Map<String, Object>> items = jdbc.query("""
                    SELECT raw_record_id, category, category_name, subcategory, grammar_content
                    FROM hsk_grammar_records WHERE level_code = ? ORDER BY raw_record_id
                    """, (rs, i) -> item(rs.getLong(1), "category", rs.getString(2), "category_name", rs.getString(3),
                    "subcategory", rs.getString(4), "content", rs.getString(5)), level);
            created += addLessons(courseId, level, order, "GRAMMAR", "grammar", "Ngữ pháp", items, GRAMMAR_PER_LESSON);
            order += lessonCount(items.size(), GRAMMAR_PER_LESSON);
        }
        if (!hasLessons(courseId, "HANZI")) {
            // The writing list shared by levels 1-2 has no single level; it goes to level 2.
            List<Map<String, Object>> items = jdbc.query("""
                    SELECT raw_record_id, hanzi FROM hsk_hanzi_records
                    WHERE level_code = ? OR (? = '2' AND level_code IS NULL) ORDER BY raw_record_id
                    """, (rs, i) -> item(rs.getLong(1), "hanzi", rs.getString(2)), level, level);
            created += addLessons(courseId, level, order, "HANZI", "characters", "Chữ Hán", items, HANZI_PER_LESSON);
        }
        return created;
    }

    private int addLessons(long courseId, String level, int startOrder, String type, String section,
                           String label, List<Map<String, Object>> items, int perLesson) {
        int lessonTotal = lessonCount(items.size(), perLesson);
        for (int n = 0; n < lessonTotal; n++) {
            List<Map<String, Object>> chunk = items.subList(n * perLesson, Math.min(items.size(), (n + 1) * perLesson));
            int order = startOrder + n + 1;
            String title = label + " HSK " + levelLabel(level) + " – phần " + (n + 1);

            List<Map<String, Object>> shown = chunk.stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>(c);
                m.remove("raw_record_id");
                return m;
            }).toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema_version", 1);
            payload.put("lesson_number", order);
            payload.put("title", title);
            payload.put("source", Map.of("book_title", courseTitle(level), "hsk_level", levelNumber(level)));
            payload.put("sections", Map.of(section, shown));

            long lessonId = jdbc.queryForObject("""
                    INSERT INTO lessons (course_id, title, lesson_order, content_type, lesson_payload)
                    VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id
                    """, Long.class, courseId, title, order, type, toJson(payload));
            for (int i = 0; i < chunk.size(); i++) {
                jdbc.update("INSERT INTO lesson_content_items (lesson_id, content_type, raw_record_id, item_order) VALUES (?, ?, ?, ?)",
                        lessonId, type, chunk.get(i).get("raw_record_id"), i + 1);
            }
        }
        return lessonTotal;
    }

    private boolean hasLessons(long courseId, String type) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM lessons WHERE course_id = ? AND content_type = ?)", Boolean.class, courseId, type));
    }

    private static int lessonCount(int items, int perLesson) {
        return (items + perLesson - 1) / perLesson;
    }

    private static Map<String, Object> item(long rawRecordId, String... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("raw_record_id", rawRecordId);
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static String blankToNull(JsonNode node) {
        String text = node.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static int levelNumber(String level) {
        return level.equals("7-9") ? 7 : Integer.parseInt(level);
    }

    private static String levelLabel(String level) {
        return level.equals("7-9") ? "7–9" : level;
    }

    private static String courseTitle(String level) {
        return "Giáo trình HSK 3.0 – Cấp " + levelLabel(level);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] readResource(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new BadRequestException("Không đọc được " + resource + ".");
        }
    }

    private JsonNode parse(byte[] bytes, String resource) {
        try {
            return objectMapper.readTree(bytes);
        } catch (IOException e) {
            throw new BadRequestException("File " + resource + " không phải JSON hợp lệ.");
        }
    }
}
