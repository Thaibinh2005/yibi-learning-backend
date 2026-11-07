package com.yibi.backend.service;

import com.yibi.backend.util.PartOfSpeechVi;
import com.yibi.backend.dto.vocabulary.DailyVocabularyResponse;
import com.yibi.backend.dto.vocabulary.VocabularyResponse;
import com.yibi.backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Read-only vocabulary catalog backed by the source-preserving HSK V6 tables. */
@Service
@RequiredArgsConstructor
public class VocabularyService {

    private final JdbcTemplate jdbc;
    private final MeasureWordService measureWordService;
    private final VocabularyLookupIndex lookupIndex;

    private static final String SELECT = """
            SELECT v.raw_record_id AS id, v.simplified AS hanzi, v.traditional AS hanzi_traditional,
                   reading.pinyin, sense.meaning_vi, v.definitions_source, v.definitions_language,
                   v.level_code, v.part_of_speech, v.audio_url, raw.created_at,
                   example.sentence_zh AS example_zh, example.sentence_pinyin AS example_pinyin,
                   example.translation_vi AS example_vi, example.audio_url AS example_audio
            FROM hsk_vocabulary_records v
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
            """;

    @Transactional(readOnly = true)
    public List<VocabularyResponse> getVocabularies(String hskLevel, String search, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<VocabularyLookupIndex.Hit> hits = lookupIndex.find(hskLevel, search, safePage, safeSize);
        if (hits.isEmpty()) {
            return List.of();
        }
        Map<Long, VocabularyResponse> byId = new HashMap<>();
        String ids = String.join(",", Collections.nCopies(hits.size(), "?"));
        jdbc.query(SELECT + " WHERE v.raw_record_id IN (" + ids + ")", this::mapRow, hits.stream().map(VocabularyLookupIndex.Hit::recordId).toArray())
                .forEach(word -> byId.put(word.getId(), word));
        List<VocabularyResponse> words = new ArrayList<>(hits.size());
        for (VocabularyLookupIndex.Hit hit : hits) {
            VocabularyResponse word = byId.get(hit.recordId());
            if (word != null) {
                word.setPinyin(hit.pinyin());
                words.add(word);
            }
        }
        return words;
    }

    @Transactional(readOnly = true)
    public VocabularyResponse getVocabularyById(Long id) {
        VocabularyResponse v = jdbc.query(SELECT + " WHERE v.raw_record_id = ?", this::mapRow, id).stream()
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Vocabulary", "id", id));
        v.setMeasureWords(measureWordService.findForNoun(v.getHanzi(), v.getPinyin()));
        return v;
    }

    @Transactional(readOnly = true)
    public List<DailyVocabularyResponse> getDailyVocabularies(String hskLevel, int days) {
        int safeDays = Math.min(Math.max(1, days), 30);
        List<VocabularyResponse> pool = getUniqueVocabularyPool(hskLevel);
        if (pool.isEmpty()) {
            return List.of();
        }

        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        LocalDate today = LocalDate.now(zone);
        List<DailyVocabularyResponse> result = new ArrayList<>();

        for (int i = 0; i < safeDays; i++) {
            LocalDate date = today.minusDays(i);
            long epochDay = date.toEpochDay();
            int seed = (int) (epochDay ^ 0x5DEECE66DL);
            if (hskLevel != null && !hskLevel.isBlank()) {
                seed += hskLevel.hashCode() * 31;
            }
            int index = Math.abs(new Random(seed).nextInt()) % pool.size();
            VocabularyResponse v = pool.get(index);

            List<String> meanings = parseMeanings(v.getMeaningVi());
            String dateLabel = formatDateLabel(date, i == 0);

            result.add(DailyVocabularyResponse.builder()
                    .id(v.getId())
                    .hanzi(v.getHanzi())
                    .hanziTraditional(v.getHanziTraditional())
                    .pinyin(v.getPinyin())
                    .meaningVi(v.getMeaningVi())
                    .meanings(meanings)
                    .hskLevel(v.getHskLevel())
                    .levelCode(v.getLevelCode())
                    .partOfSpeech(v.getPartOfSpeech() != null ? v.getPartOfSpeech() : "Từ vựng")
                    .audioUrl(v.getAudioUrl())
                    .date(date)
                    .dateLabel(dateLabel)
                    .isToday(i == 0)
                    .build());
        }

        attachMeasureWords(result);
        return result;
    }

    @Transactional(readOnly = true)
    public DailyVocabularyResponse getRandomVocabulary(String hskLevel) {
        List<VocabularyResponse> pool = getUniqueVocabularyPool(hskLevel);
        if (pool.isEmpty()) {
            throw new ResourceNotFoundException("Vocabulary", "hskLevel", hskLevel);
        }
        int index = new Random().nextInt(pool.size());
        VocabularyResponse v = pool.get(index);
        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        LocalDate today = LocalDate.now(zone);

        return DailyVocabularyResponse.builder()
                .id(v.getId())
                .hanzi(v.getHanzi())
                .hanziTraditional(v.getHanziTraditional())
                .pinyin(v.getPinyin())
                .meaningVi(v.getMeaningVi())
                .meanings(parseMeanings(v.getMeaningVi()))
                .hskLevel(v.getHskLevel())
                .levelCode(v.getLevelCode())
                .partOfSpeech(v.getPartOfSpeech() != null ? v.getPartOfSpeech() : "Từ vựng")
                .audioUrl(v.getAudioUrl())
                .date(today)
                .dateLabel("Từ vựng ngẫu nhiên hôm nay")
                .isToday(true)
                .measureWords(measureWordService.findForNoun(v.getHanzi(), v.getPinyin()))
                .build();
    }

    private List<VocabularyResponse> getUniqueVocabularyPool(String hskLevel) {
        StringBuilder sql = new StringBuilder("""
            SELECT DISTINCT ON (v.simplified)
                   v.raw_record_id AS id, v.simplified AS hanzi, v.traditional AS hanzi_traditional,
                   reading.pinyin, sense.meaning_vi, v.definitions_source, v.definitions_language,
                   v.level_code, v.part_of_speech, v.audio_url, raw.created_at,
                   NULL AS example_zh, NULL AS example_pinyin, NULL AS example_vi, NULL AS example_audio
            FROM hsk_vocabulary_records v
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
            WHERE sense.meaning_vi IS NOT NULL
        """);

        List<Object> params = new ArrayList<>();
        if (hskLevel != null && !hskLevel.isBlank() && !hskLevel.equalsIgnoreCase("all") && !hskLevel.equalsIgnoreCase("Tất cả")) {
            String clean = hskLevel.trim();
            if (clean.toUpperCase().startsWith("HSK")) {
                clean = clean.substring(3).trim();
            }
            sql.append(" AND v.level_code = ? ");
            params.add(clean);
        }

        sql.append(" ORDER BY v.simplified, v.level_code NULLS LAST, v.raw_record_id LIMIT 1000");
        return jdbc.query(sql.toString(), this::mapRow, params.toArray());
    }

    private List<String> parseMeanings(String meaningVi) {
        if (meaningVi == null || meaningVi.isBlank()) {
            return List.of();
        }
        String[] parts = meaningVi.split(";");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    private String formatDateLabel(LocalDate date, boolean isToday) {
        String dayOfWeekStr = switch (date.getDayOfWeek()) {
            case MONDAY -> "Thứ Hai";
            case TUESDAY -> "Thứ Ba";
            case WEDNESDAY -> "Thứ Tư";
            case THURSDAY -> "Thứ Năm";
            case FRIDAY -> "Thứ Sáu";
            case SATURDAY -> "Thứ Bảy";
            case SUNDAY -> "Chủ Nhật";
        };
        if (isToday) {
            return "%s, %d tháng %d, %d".formatted(dayOfWeekStr, date.getDayOfMonth(), date.getMonthValue(), date.getYear());
        } else {
            return "%d tháng %d, %d".formatted(date.getDayOfMonth(), date.getMonthValue(), date.getYear());
        }
    }

    private void attachMeasureWords(List<DailyVocabularyResponse> items) {
        java.util.Map<String, String> nouns = new java.util.HashMap<>();
        items.forEach(d -> nouns.putIfAbsent(d.getHanzi(), d.getPinyin()));
        var found = measureWordService.findForNouns(nouns);
        items.forEach(d -> d.setMeasureWords(
                found.getOrDefault(MeasureWordService.cleanNoun(d.getHanzi()), List.of())));
    }

    private VocabularyResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
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
    }
}
