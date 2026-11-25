package com.yibi.backend.service.mocktest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where sample papers live: the mock_tests table, one row per paper, and mock_test_versions, every published version
 * (an attempt is always shown and scored with the version it started on).
 *
 * <p>Papers bundled as data/mocktests/*.json (made by mocktest_publish.py, shared with every contributor) are loaded
 * at startup: a new file becomes a published paper, a changed file a new version — unless an admin has since edited
 * that paper in the app (origin EDITED), whose edits then win.
 */
@Slf4j
@Component
public class MockTestCatalog {

    static final String BUNDLES = "classpath:data/mocktests/*.json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<String, JsonNode> versions = new ConcurrentHashMap<>();

    public MockTestCatalog(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Published(String id, int version, JsonNode paper) {}

    /** Bundled papers by id, read from the classpath. */
    public static Map<String, JsonNode> readBundles(ObjectMapper json) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        try {
            for (Resource file : new PathMatchingResourcePatternResolver().getResources(BUNDLES)) {
                try (InputStream in = file.getInputStream()) {
                    JsonNode paper = json.readTree(in);
                    out.put(paper.path("id").asText(), paper);
                }
            }
        } catch (IOException ex) {
            log.warn("Could not read bundled mock test papers: {}", ex.getMessage());
        }
        return out;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncBundles() {
        int added = 0;
        int updated = 0;
        for (JsonNode paper : readBundles(json).values()) {
            String id = paper.path("id").asText();
            String hash = hash(paper);
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT origin, bundle_hash FROM mock_tests WHERE id = ?", id);
            if (rows.isEmpty()) {
                jdbc.update("""
                        INSERT INTO mock_tests (id, level, title, status, origin, published_version, bundle_hash, published_at)
                        VALUES (?, ?, ?, 'PUBLISHED', 'BUNDLE', 1, ?, now())
                        """, id, paper.path("level").asInt(), paper.path("title").asText(id), hash);
                jdbc.update("INSERT INTO mock_test_versions (test_id, version, paper) VALUES (?, 1, ?::jsonb)", id, paper.toString());
                added++;
            } else if ("BUNDLE".equals(rows.get(0).get("origin")) && !hash.equals(rows.get(0).get("bundle_hash"))) {
                int version = nextVersion(id);
                jdbc.update("INSERT INTO mock_test_versions (test_id, version, paper) VALUES (?, ?, ?::jsonb)", id, version, paper.toString());
                jdbc.update("""
                        UPDATE mock_tests SET published_version = ?, bundle_hash = ?, level = ?, title = ?, updated_at = now(),
                               published_at = now() WHERE id = ?
                        """, version, hash, paper.path("level").asInt(), paper.path("title").asText(id), id);
                updated++;
            }
        }
        if (added + updated > 0) log.info("Bundled mock test papers: {} added, {} updated", added, updated);
    }

    public int nextVersion(String testId) {
        Integer max = jdbc.queryForObject("SELECT max(version) FROM mock_test_versions WHERE test_id = ?", Integer.class, testId);
        return max == null ? 1 : max + 1;
    }

    /** Papers learners can see, easiest level first. */
    public List<Published> published() {
        List<Published> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT id, published_version FROM mock_tests WHERE status = 'PUBLISHED' AND published_version IS NOT NULL
                ORDER BY level, id
                """)) {
            String id = (String) row.get("id");
            int version = ((Number) row.get("published_version")).intValue();
            out.add(new Published(id, version, paper(id, version)));
        }
        return out;
    }

    /** The version a new attempt gets, or not found when the paper is not open to learners. */
    public int publishedVersion(String testId) {
        List<Integer> found = jdbc.queryForList(
                "SELECT published_version FROM mock_tests WHERE id = ? AND status = 'PUBLISHED' AND published_version IS NOT NULL",
                Integer.class, testId);
        if (found.isEmpty()) throw new ResourceNotFoundException("Không tìm thấy đề thi " + testId);
        return found.get(0);
    }

    /** One published version; versions never change, so they are cached. */
    public JsonNode paper(String testId, int version) {
        return versions.computeIfAbsent(testId + "#" + version, k -> {
            List<String> found = jdbc.queryForList("SELECT paper::text FROM mock_test_versions WHERE test_id = ? AND version = ?",
                    String.class, testId, version);
            if (found.isEmpty()) throw new ResourceNotFoundException("Không tìm thấy đề thi " + testId);
            try {
                return json.readTree(found.get(0));
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    static String hash(JsonNode paper) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(paper.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
