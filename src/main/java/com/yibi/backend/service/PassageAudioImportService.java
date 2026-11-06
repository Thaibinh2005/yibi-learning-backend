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
 * Uploads passage mp3 files to MinIO and links each one to its LISTENING lesson. A file is named after
 * the passage it reads, e.g. L3-P2.mp3 (the repository's data/passage-audio folder), or after the lesson
 * id, e.g. 123.mp3 (the output of scripts/generate_passage_audio.py).
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
            files = listing.filter(p -> p.getFileName().toString().matches("(\\d+|L\\d+-P\\d+)\\.mp3")).sorted().toList();
        }
        int linked = 0;
        for (Path file : files) {
            String name = file.getFileName().toString().replace(".mp3", "");
            List<Long> ids = name.startsWith("L")
                    ? jdbc.queryForList("SELECT id FROM lessons WHERE content_type = 'LISTENING' AND lesson_payload -> 'source' ->> 'passage_key' = ?", Long.class, name)
                    : jdbc.queryForList("SELECT id FROM lessons WHERE content_type = 'LISTENING' AND id = ?", Long.class, Long.parseLong(name));
            if (ids.isEmpty()) {
                log.warn("{} does not belong to a listening lesson; skipped.", file.getFileName());
                continue;
            }
            long lessonId = ids.get(0);
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
