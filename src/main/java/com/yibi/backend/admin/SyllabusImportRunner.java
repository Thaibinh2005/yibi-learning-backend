package com.yibi.backend.admin;

import com.yibi.backend.service.CurriculumImportService;
import com.yibi.backend.service.HanziSyllableImportService;
import com.yibi.backend.service.SyllabusImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * CLI runner that loads the official HSK syllabus lists into the hsk_* tables.
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments=--import-syllabus
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyllabusImportRunner implements ApplicationRunner {

    private static final String TRIGGER_FLAG = "import-syllabus";

    private final SyllabusImportService syllabusImportService;
    private final CurriculumImportService curriculumImportService;
    private final HanziSyllableImportService hanziSyllableImportService;

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(TRIGGER_FLAG)) {
            return;
        }
        SyllabusImportService.ImportResult result = syllabusImportService.importSyllabus();
        log.info("Syllabus import finished: skipped={}, vocabulary={}, characters={}",
                result.skipped(), result.vocabulary(), result.characters());
        CurriculumImportService.Result curriculum = curriculumImportService.run();
        log.info("Curriculum import finished: grammar rows={}, meanings={}, courses created={}, lessons created={}",
                curriculum.grammarRows(), curriculum.meanings(), curriculum.courses(), curriculum.lessons());
        HanziSyllableImportService.Result hanzi = hanziSyllableImportService.run();
        log.info("Hanzi syllables import finished: characters={}, new readings={}, without a reading={}",
                hanzi.characters(), hanzi.readings(), hanzi.skipped());
        System.exit(0);
    }
}
