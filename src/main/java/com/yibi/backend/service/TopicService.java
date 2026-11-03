package com.yibi.backend.service;

import com.yibi.backend.util.PartOfSpeechVi;
import com.yibi.backend.dto.topic.TopicResponse;
import com.yibi.backend.dto.vocabulary.VocabularyResponse;
import com.yibi.backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TopicService {

    private final JdbcTemplate jdbc;
    private final MeasureWordService measureWordService;

    @Transactional(readOnly = true)
    public List<TopicResponse> getTopics(String hskLevel, String search, String sort) {
        // Filter by the level of the words inside each topic, not the topic's own
        // label: topics are labelled HSK 1-5 only, but contain words of every level.
        String level = normalizeLevel(hskLevel);
        List<Object> params = new ArrayList<>();

        StringBuilder sql = new StringBuilder("""
            SELECT t.id, t.slug, t.title_vi, t.title_zh, t.icon, t.description, t.hsk_level,
                   COUNT(DISTINCT v.simplified) as word_count
            FROM topics t
            LEFT JOIN vocabulary_topics vt ON vt.topic_id = t.id
            LEFT JOIN hsk_vocabulary_records v ON v.raw_record_id = vt.raw_record_id
        """);
        if (level != null) {
            sql.append(" AND v.level_code = ? ");
            params.add(level);
        }
        sql.append(" WHERE 1=1 ");

        if (search != null && !search.isBlank()) {
            sql.append(" AND (t.title_vi ILIKE ? OR t.title_zh ILIKE ? OR t.description ILIKE ?) ");
            String term = "%" + search.trim() + "%";
            params.add(term);
            params.add(term);
            params.add(term);
        }

        sql.append(" GROUP BY t.id, t.slug, t.title_vi, t.title_zh, t.icon, t.description, t.hsk_level ");
        if (level != null) {
            sql.append(" HAVING COUNT(DISTINCT v.simplified) > 0 ");
        }

        if ("az".equalsIgnoreCase(sort)) {
            sql.append(" ORDER BY t.title_vi ASC ");
        } else {
            // Default "count": sort by most words first
            sql.append(" ORDER BY word_count DESC, t.id ASC ");
        }

        List<TopicResponse> topics = jdbc.query(sql.toString(), this::mapTopicRow, params.toArray());

        if (topics.isEmpty()) {
            return topics;
        }

        // Fetch sample words for returned topics
        List<Long> topicIds = topics.stream().map(TopicResponse::getId).toList();
        Map<Long, List<TopicResponse.SampleWordDto>> sampleMap = fetchSampleWords(topicIds, level);

        for (TopicResponse t : topics) {
            t.setSampleWords(sampleMap.getOrDefault(t.getId(), List.of()));
        }

        return topics;
    }

    @Transactional(readOnly = true)
    public TopicResponse getTopicBySlug(String slug, String hskLevel) {
        String level = normalizeLevel(hskLevel);
        String sql = """
            SELECT t.id, t.slug, t.title_vi, t.title_zh, t.icon, t.description, t.hsk_level,
                   COUNT(DISTINCT v.simplified) as word_count
            FROM topics t
            LEFT JOIN vocabulary_topics vt ON vt.topic_id = t.id
            LEFT JOIN hsk_vocabulary_records v ON v.raw_record_id = vt.raw_record_id
                 AND (CAST(? AS VARCHAR) IS NULL OR v.level_code = ?)
            WHERE t.slug = ?
            GROUP BY t.id, t.slug, t.title_vi, t.title_zh, t.icon, t.description, t.hsk_level
        """;

        List<TopicResponse> list = jdbc.query(sql, this::mapTopicRow, level, level, slug);
        if (list.isEmpty()) {
            throw new ResourceNotFoundException("Topic", "slug", slug);
        }

        TopicResponse topic = list.get(0);
        Map<Long, List<TopicResponse.SampleWordDto>> sampleMap = fetchSampleWords(List.of(topic.getId()), level);
        topic.setSampleWords(sampleMap.getOrDefault(topic.getId(), List.of()));
        return topic;
    }

    @Transactional(readOnly = true)
    public List<VocabularyResponse> getVocabulariesByTopic(String slug, String hskLevel, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        String level = normalizeLevel(hskLevel);

        String sql = """
            SELECT * FROM (
                SELECT DISTINCT ON (v.simplified)
                       v.raw_record_id AS id, v.simplified AS hanzi, v.traditional AS hanzi_traditional,
                       reading.pinyin, sense.meaning_vi, v.definitions_source, v.definitions_language,
                       v.level_code, v.part_of_speech, v.audio_url, raw.created_at,
                       example.sentence_zh AS example_zh, example.sentence_pinyin AS example_pinyin,
                       example.translation_vi AS example_vi, example.audio_url AS example_audio
                FROM topics t
                JOIN vocabulary_topics vt ON vt.topic_id = t.id
                JOIN hsk_vocabulary_records v ON v.raw_record_id = vt.raw_record_id
                JOIN hsk_raw_records raw ON raw.id = v.raw_record_id
                LEFT JOIN LATERAL (
                    SELECT r.pinyin FROM hsk_vocabulary_readings r
                    WHERE r.raw_record_id = v.raw_record_id ORDER BY r.reading_order LIMIT 1
                ) reading ON TRUE
                LEFT JOIN LATERAL (
                    SELECT string_agg(s.meaning, '; ' ORDER BY s.sense_order) AS meaning_vi
                    FROM hsk_vocabulary_senses s
                    WHERE s.raw_record_id = v.raw_record_id AND lower(s.language_code) = 'vi'
                ) sense ON TRUE
                LEFT JOIN LATERAL (
                    SELECT e.sentence_zh, e.sentence_pinyin, e.translation_vi, e.audio_url
                    FROM hsk_vocabulary_examples e
                    JOIN hsk_vocabulary_records ev ON ev.raw_record_id = e.raw_record_id
                    WHERE ev.simplified = v.simplified
                    ORDER BY (ev.level_code IS NOT DISTINCT FROM v.level_code) DESC, e.id
                    LIMIT 1
                ) example ON TRUE
                WHERE t.slug = ?
                  AND (CAST(? AS VARCHAR) IS NULL OR v.level_code = ?)
                ORDER BY v.simplified, v.level_code NULLS LAST, v.raw_record_id
            ) deduped
            ORDER BY deduped.level_code NULLS LAST, deduped.hanzi
            LIMIT ? OFFSET ?
        """;

        List<VocabularyResponse> words = jdbc.query(sql, (rs, rowNum) -> {
            String levelCode = rs.getString("level_code");
            Integer numericLevel = null;
            if (levelCode != null && levelCode.matches("[1-6]")) numericLevel = Integer.valueOf(levelCode);
            String definitions = rs.getString("definitions_source");
            String definitionsLanguage = rs.getString("definitions_language");

            return VocabularyResponse.builder()
                    .id(rs.getLong("id"))
                    .hanzi(rs.getString("hanzi"))
                    .hanziTraditional(rs.getString("hanzi_traditional"))
                    .pinyin(rs.getString("pinyin"))
                    .meaningVi(rs.getString("meaning_vi"))
                    .meaningEn("en".equalsIgnoreCase(definitionsLanguage) ? definitions : null)
                    .hskLevel(numericLevel)
                    .levelCode(levelCode)
                    .partOfSpeech(PartOfSpeechVi.translate(rs.getString("part_of_speech")))
                    .exampleSentence(rs.getString("example_zh"))
                    .examplePinyin(rs.getString("example_pinyin"))
                    .exampleTranslation(rs.getString("example_vi"))
                    .exampleAudioUrl(rs.getString("example_audio"))
                    .audioUrl(rs.getString("audio_url"))
                    .createdAt(rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime())
                    .updatedAt(rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime())
                    .build();
        }, slug, level, level, safeSize, (long) safePage * safeSize);

        Map<String, String> nouns = new HashMap<>();
        words.forEach(w -> nouns.putIfAbsent(w.getHanzi(), w.getPinyin()));
        var found = measureWordService.findForNouns(nouns);
        words.forEach(w -> w.setMeasureWords(
                found.getOrDefault(MeasureWordService.cleanNoun(w.getHanzi()), List.of())));
        return words;
    }

    /** "HSK 6", "hsk6", "6" -> "6"; "HSK 7-9", "7-9", "7" -> "7-9"; "Tất cả"/"all"/blank -> null. */
    static String normalizeLevel(String hskLevel) {
        if (hskLevel == null) return null;
        String s = hskLevel.trim().toUpperCase().replace("HSK", "").replace(" ", "");
        if (s.matches("[1-6]")) return s;
        if (s.matches("7-9|7|8|9")) return "7-9";
        return null;
    }

    private Map<Long, List<TopicResponse.SampleWordDto>> fetchSampleWords(List<Long> topicIds, String level) {
        if (topicIds.isEmpty()) return Map.of();

        String inClause = String.join(",", topicIds.stream().map(String::valueOf).toList());
        String sql = """
            WITH distinct_words AS (
                SELECT DISTINCT ON (vt.topic_id, v.simplified)
                    vt.topic_id,
                    v.raw_record_id as id,
                    v.simplified as hanzi,
                    r.pinyin,
                    s.meaning as meaning_vi,
                    v.level_code
                FROM vocabulary_topics vt
                JOIN hsk_vocabulary_records v ON v.raw_record_id = vt.raw_record_id
                LEFT JOIN LATERAL (
                    SELECT pinyin FROM hsk_vocabulary_readings r WHERE r.raw_record_id = v.raw_record_id ORDER BY reading_order LIMIT 1
                ) r ON TRUE
                LEFT JOIN LATERAL (
                    SELECT meaning FROM hsk_vocabulary_senses s WHERE s.raw_record_id = v.raw_record_id AND lower(s.language_code) = 'vi' ORDER BY sense_order LIMIT 1
                ) s ON TRUE
                WHERE vt.topic_id IN (%s)
                  AND (CAST(? AS VARCHAR) IS NULL OR v.level_code = ?)
                ORDER BY vt.topic_id, v.simplified, v.level_code NULLS LAST, v.raw_record_id
            ),
            ranked AS (
                SELECT *,
                    ROW_NUMBER() OVER(PARTITION BY topic_id ORDER BY length(hanzi), hanzi) as rn
                FROM distinct_words
            )
            SELECT topic_id, id, hanzi, pinyin, meaning_vi, level_code
            FROM ranked
            WHERE rn <= 5
        """.formatted(inClause);

        Map<Long, List<TopicResponse.SampleWordDto>> map = new HashMap<>();
        jdbc.query(sql, (rs) -> {
            Long topicId = rs.getLong("topic_id");
            String meaning = rs.getString("meaning_vi");
            if (meaning != null && meaning.contains(";")) {
                meaning = meaning.split(";")[0].trim();
            }

            TopicResponse.SampleWordDto dto = TopicResponse.SampleWordDto.builder()
                    .id(rs.getLong("id"))
                    .hanzi(rs.getString("hanzi"))
                    .pinyin(rs.getString("pinyin"))
                    .meaningVi(meaning)
                    .levelCode(rs.getString("level_code"))
                    .build();

            map.computeIfAbsent(topicId, k -> new ArrayList<>()).add(dto);
        }, level, level);

        return map;
    }

    private TopicResponse mapTopicRow(ResultSet rs, int rowNum) throws SQLException {
        return TopicResponse.builder()
                .id(rs.getLong("id"))
                .slug(rs.getString("slug"))
                .titleVi(rs.getString("title_vi"))
                .titleZh(rs.getString("title_zh"))
                .icon(rs.getString("icon"))
                .description(rs.getString("description"))
                .hskLevel(rs.getString("hsk_level"))
                .wordCount(rs.getInt("word_count"))
                .build();
    }
}
