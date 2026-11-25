package com.yibi.backend.admin;

import com.yibi.backend.service.mocktest.MockTestAdminService;
import com.yibi.backend.storage.MediaStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads a folder made by the mock test scripts (exam.json, img/, audio/, peaks.json) as a DRAFT paper, as if it
 * had been uploaded, so it can be checked and published on the admin page. With the original recording given, the
 * review page can also re-cut clips.
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=0 --import-mock-test=../MocktestHSK3.0/output/hsk2 --mock-test-audio=../MocktestHSK3.0/HSK2_1110.mp3"
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockTestImportRunner implements ApplicationRunner {

    private final MockTestAdminService service;
    private final MediaStorage media;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> folders = args.getOptionValues("import-mock-test");
        if (folders == null || folders.isEmpty()) return;
        List<String> audios = args.getOptionValues("mock-test-audio");
        for (int i = 0; i < folders.size(); i++) {
            Path folder = Path.of(folders.get(i));
            String audioKey = null;
            if (audios != null && i < audios.size()) {
                Path audio = Path.of(audios.get(i));
                String name = audio.getFileName().toString();
                try (InputStream in = Files.newInputStream(audio)) {
                    audioKey = media.store(in, name.substring(name.lastIndexOf('.') + 1));
                }
            }
            String testId = service.ingestFolder(folder, audioKey, null, null);
            log.info("Mock test {} loaded as draft {}", folder, testId);
        }
        System.exit(0);
    }
}
