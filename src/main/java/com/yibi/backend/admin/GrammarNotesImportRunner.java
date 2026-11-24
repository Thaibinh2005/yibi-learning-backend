package com.yibi.backend.admin;

import com.yibi.backend.service.GrammarNotesImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * CLI runner that loads the grammar notes into grammar_notes (needs the syllabus imported first).
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments=--import-grammar-notes
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrammarNotesImportRunner implements ApplicationRunner {

    private static final String TRIGGER_FLAG = "import-grammar-notes";

    private final GrammarNotesImportService grammarNotesImportService;

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(TRIGGER_FLAG)) {
            return;
        }
        GrammarNotesImportService.Result result = grammarNotesImportService.run();
        log.info("Grammar notes import finished: notes={}, records={}, unmatched={}",
                result.notes(), result.records(), result.unmatched());
        System.exit(result.unmatched() == 0 ? 0 : 1);
    }
}
