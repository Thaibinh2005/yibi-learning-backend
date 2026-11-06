package com.yibi.backend.service;

import com.yibi.backend.config.MinioConfig;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Uploads the mp3 files made by scripts/generate_passage_audio.py to MinIO and links each one to its
 * LISTENING lesson. A file is named after the lesson id it belongs to, e.g. 123.mp3.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PassageAudioImportService {

    private final JdbcTemplate jdbc;
    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    public int importFrom(Path directory) throws Exception {
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing.filter(p -> p.getFileName().toString().matches("\\d+\\.mp3")).sorted().toList();
        }
        int linked = 0;
        for (Path file : files) {
            long lessonId = Long.parseLong(file.getFileName().toString().replace(".mp3", ""));
            Boolean isListening = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM lessons WHERE id = ? AND content_type = 'LISTENING')", Boolean.class, lessonId);
            if (!Boolean.TRUE.equals(isListening)) {
                log.warn("{} does not belong to a listening lesson; skipped.", file.getFileName());
                continue;
            }
            String object = "passages/" + lessonId + ".mp3";
            minioClient.uploadObject(UploadObjectArgs.builder()
                    .bucket(minioConfig.getBucketName()).object(object).filename(file.toString()).contentType("audio/mpeg").build());
            String url = minioConfig.getEndpoint() + "/" + minioConfig.getBucketName() + "/" + object;
            jdbc.update("UPDATE lessons SET lesson_payload = jsonb_set(lesson_payload, '{sections,listenings,0,audio_url}', to_jsonb(?::text)) WHERE id = ?",
                    url, lessonId);
            linked++;
        }
        return linked;
    }
}
