package com.yibi.backend.admin;

import com.yibi.backend.service.PassageAudioImportService;
import com.yibi.backend.service.PassageGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * CLI runner for reading and listening lessons.
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments="--generate-passages --limit=20"
 * mvn spring-boot:run -Dspring-boot.run.arguments="--import-passage-audio=/path/to/mp3-folder"
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PassageRunner implements ApplicationRunner {

    private final PassageGeneratorService generator;
    private final PassageAudioImportService audioImport;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (args.containsOption("generate-passages")) {
            int limit = args.containsOption("limit") ? Integer.parseInt(args.getOptionValues("limit").get(0)) : Integer.MAX_VALUE;
            PassageGeneratorService.Result result = generator.generateAll(limit);
            log.info("Passage generation finished: created={}, already there={}, stopped by quota={}",
                    result.created(), result.skipped(), result.stoppedByQuota());
            System.exit(0);
        }
        if (args.containsOption("import-passage-audio")) {
            int linked = audioImport.importFrom(Path.of(args.getOptionValues("import-passage-audio").get(0)));
            log.info("Passage audio import finished: {} files linked.", linked);
            System.exit(0);
        }
    }
}
