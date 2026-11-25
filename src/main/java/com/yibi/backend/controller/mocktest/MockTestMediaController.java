package com.yibi.backend.controller.mocktest;

import com.yibi.backend.storage.MediaStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pictures and clips of imported sample papers, open without login like the paper list. Only files registered in
 * mock_test_media are served, so other files in the same storage (learners' uploads) are never reachable here.
 * Keys never change content, so browsers may keep them for a year; byte ranges let the review page seek.
 */
@RestController
@RequiredArgsConstructor
public class MockTestMediaController {

    private final JdbcTemplate jdbc;
    private final MediaStorage media;
    private final Map<String, String> types = new ConcurrentHashMap<>();

    @GetMapping("/api/mock-tests/media/{year}/{month}/{file}")
    public ResponseEntity<Resource> file(@PathVariable String year, @PathVariable String month, @PathVariable String file) {
        String key = year + "/" + month + "/" + file;
        String type = types.get(key);
        if (type == null) {
            List<String> found = jdbc.queryForList("SELECT content_type FROM mock_test_media WHERE storage_key = ?", String.class, key);
            if (found.isEmpty()) return ResponseEntity.notFound().build();
            type = found.get(0);
            types.put(key, type);
        }
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(type))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                    .body(media.load(key));
        } catch (IOException | IllegalArgumentException ex) {
            types.remove(key);
            return ResponseEntity.notFound().build();
        }
    }
}
