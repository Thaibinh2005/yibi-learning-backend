package com.yibi.backend.service;

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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;

/**
 * Imports the official HSK syllabus lists (data/hsk_syllabus_2025.json) into the versioned hsk_* tables.
 * Re-running with an unchanged file does nothing: the file is identified by its SHA-256.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyllabusImportService {

    private static final String RESOURCE = "data/hsk_syllabus_2025.json";
    static final String DATASET_KEY = "hsk-syllabus-2025-11";
    private static final int HANZI_ROW_OFFSET = 100_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public record ImportResult(boolean skipped, int vocabulary, int characters) {}

    @Transactional
    public ImportResult importSyllabus() {
        byte[] bytes = readResource();
        String sha = sha256(bytes);
        JsonNode doc = parse(bytes);

        long datasetId = upsertDataset(doc.path("source"));
        Boolean already = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM hsk_source_files WHERE dataset_id = ? AND sha256 = ?)",
                Boolean.class, datasetId, sha);
        if (Boolean.TRUE.equals(already)) {
            log.info("Syllabus file {} is already imported; nothing to do.", sha);
            return new ImportResult(true, 0, 0);
        }

        int vocabularyCount = doc.path("vocabulary").size();
        int characterCount = countCharacters(doc.path("characters"));
        long fileId = jdbc.queryForObject("""
                INSERT INTO hsk_source_files (dataset_id, relative_path, sha256, format, byte_size, detected_encoding, record_count)
                VALUES (?, ?, ?, 'JSON', ?, 'UTF-8', ?) RETURNING id
                """, Long.class, datasetId, RESOURCE, sha, bytes.length, vocabularyCount + characterCount);

        for (JsonNode v : doc.path("vocabulary")) {
            insertVocabulary(fileId, v);
        }
        int row = HANZI_ROW_OFFSET;
        Iterator<Map.Entry<String, JsonNode>> sections = doc.path("characters").fields();
        while (sections.hasNext()) {
            Map.Entry<String, JsonNode> section = sections.next();
            String levelCode = levelCodeOf(section.getKey());
            for (JsonNode ch : section.getValue()) {
                insertHanzi(fileId, ++row, levelCode, section.getKey(), ch.asText());
            }
        }
        log.info("Imported syllabus: {} vocabulary rows, {} character rows.", vocabularyCount, characterCount);
        return new ImportResult(false, vocabularyCount, characterCount);
    }

    private long upsertDataset(JsonNode source) {
        jdbc.update("""
                INSERT INTO hsk_source_datasets
                  (dataset_key, standard_code, version_code, repository_url, license_name, license_notes, verification_status)
                VALUES (?, 'NEW_HSK', '2025', ?, 'Official public syllabus', ?, 'UNVERIFIED')
                ON CONFLICT (dataset_key) DO NOTHING
                """, DATASET_KEY, source.path("origin").asText(),
                source.path("title").asText() + ", issued " + source.path("issued").asText()
                        + ", in force " + source.path("inForce").asText() + ". Extracted by script.");
        return jdbc.queryForObject("SELECT id FROM hsk_source_datasets WHERE dataset_key = ?", Long.class, DATASET_KEY);
    }

    private void insertVocabulary(long fileId, JsonNode v) {
        long rawId = insertRaw(fileId, v.path("seq").asInt(), "VOCABULARY", v.toString());
        String pos = v.path("pos").isNull() ? null : v.path("pos").asText();
        jdbc.update("""
                INSERT INTO hsk_vocabulary_records (raw_record_id, level_code, source_level_label, simplified, part_of_speech)
                VALUES (?, ?, ?, ?, ?)
                """, rawId, v.path("level").asText(), v.path("levelLabel").asText(), v.path("word").asText(), pos);
        jdbc.update("INSERT INTO hsk_vocabulary_readings (raw_record_id, reading_order, pinyin) VALUES (?, 1, ?)",
                rawId, v.path("pinyin").asText());
    }

    private void insertHanzi(long fileId, int rowNumber, String levelCode, String label, String hanzi) {
        long rawId = insertRaw(fileId, rowNumber, "HANZI", hanzi);
        jdbc.update("INSERT INTO hsk_hanzi_records (raw_record_id, level_code, source_level_label, hanzi) VALUES (?, ?, ?, ?)",
                rawId, levelCode, label, hanzi);
    }

    private long insertRaw(long fileId, int rowNumber, String kind, String rawText) {
        return jdbc.queryForObject("""
                INSERT INTO hsk_raw_records (source_file_id, source_row_number, record_kind, raw_text, record_sha256)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, fileId, rowNumber, kind, rawText, sha256(rawText.getBytes(StandardCharsets.UTF_8)));
    }

    /** Recognition lists map to one level; a writing list spanning levels 1-2 has no single level. */
    private static String levelCodeOf(String sectionLabel) {
        if (sectionLabel.contains("~")) return null;
        if (sectionLabel.contains("七—九级")) return "7-9";
        String[] names = {"一级", "二级", "三级", "四级", "五级", "六级"};
        for (int i = 0; i < names.length; i++) {
            if (sectionLabel.contains(names[i])) return String.valueOf(i + 1);
        }
        return null;
    }

    private static int countCharacters(JsonNode characters) {
        int total = 0;
        for (JsonNode section : characters) total += section.size();
        return total;
    }

    private static byte[] readResource() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new BadRequestException("Không đọc được " + RESOURCE + ".");
        }
    }

    private JsonNode parse(byte[] bytes) {
        try {
            return objectMapper.readTree(bytes);
        } catch (IOException e) {
            throw new BadRequestException("File " + RESOURCE + " không phải JSON hợp lệ.");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
