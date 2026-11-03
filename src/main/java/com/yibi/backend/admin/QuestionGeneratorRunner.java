package com.yibi.backend.admin;

import com.yibi.backend.dto.admin.GeneratedQuestionPreview;
import com.yibi.backend.dto.admin.GeneratorRunResult;
import com.yibi.backend.dto.admin.QuestionGeneratorParams;
import com.yibi.backend.service.QuestionGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CLI runner for generating and importing questions across HSK levels.
 *
 * <h3>Examples</h3>
 * <pre>
 * # Run single combination:
 * java -jar yibi-backend.jar --generate-questions --gen.level=1 --gen.type=PHONETICS_SELECT --gen.limit=50 --gen.mode=dry-run
 *
 * # Run ALL levels (1-6, 7-9) and ALL types (50 each) and import to DB:
 * java -jar yibi-backend.jar --generate-questions --gen.all --gen.limit=50 --gen.mode=import --gen.publish
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionGeneratorRunner implements ApplicationRunner {

    private static final String TRIGGER_FLAG = "generate-questions";
    private static final List<String> ALL_LEVELS = List.of("1", "2", "3", "4", "5", "6", "7-9");
    private static final List<String> ALL_TYPES  = List.of("PHONETICS_SELECT", "MEANING_HANZI", "VOCAB_MEANING");

    private final QuestionGeneratorService questionGeneratorService;

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(TRIGGER_FLAG)) {
            return;
        }

        boolean runAll = args.containsOption("gen.all");
        String levelArg = firstOr(args, "gen.level", runAll ? "all" : "1");
        String typeArg  = firstOr(args, "gen.type", runAll ? "all" : "PHONETICS_SELECT");
        int    limit    = Integer.parseInt(firstOr(args, "gen.limit", "50"));
        String mode     = firstOr(args, "gen.mode", "dry-run");
        boolean autoPublish = args.containsOption("gen.publish");

        List<String> targetLevels = "all".equalsIgnoreCase(levelArg) ? ALL_LEVELS : List.of(levelArg);
        List<String> targetTypes  = "all".equalsIgnoreCase(typeArg)  ? ALL_TYPES  : List.of(typeArg);

        log.info("=== Question Generator CLI Batch ===");
        log.info("Levels: {} | Types: {} | Limit/batch: {} | Mode: {} | AutoPublish: {}",
                targetLevels, targetTypes, limit, mode, autoPublish);

        List<GeneratorRunResult> results = new ArrayList<>();
        int totalGenerated = 0;
        int totalImported  = 0;
        int totalExisted   = 0;
        int totalSkipped   = 0;

        for (String lvl : targetLevels) {
            for (String typ : targetTypes) {
                QuestionGeneratorParams params = new QuestionGeneratorParams();
                params.setLevelCode(lvl);
                params.setQuestionType(typ);
                params.setLimit(limit);
                params.setMode(mode);

                try {
                    GeneratorRunResult r = questionGeneratorService.generate(params);
                    results.add(r);
                    totalGenerated += r.getGenerated();
                    totalImported  += r.getImported();
                    totalExisted   += r.getAlreadyExisted();
                    totalSkipped   += r.getSkipped();

                    if (!runAll && targetLevels.size() == 1 && targetTypes.size() == 1) {
                        printSingleResult(r);
                    }
                } catch (Exception ex) {
                    log.error("Failed batch level={} type={}: {}", lvl, typ, ex.getMessage(), ex);
                }
            }
        }

        int publishedCount = 0;
        if ("import".equals(mode) && autoPublish) {
            publishedCount = questionGeneratorService.publishDraftQuestions(null, null);
        }

        printBatchSummary(mode, results, totalGenerated, totalImported, totalExisted, totalSkipped, publishedCount, autoPublish);
        System.exit(0);
    }

    private static void printBatchSummary(
            String mode, List<GeneratorRunResult> results,
            int totalGen, int totalImp, int totalExist, int totalSkip,
            int publishedCount, boolean autoPublish) {

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.printf ("║  QUESTION GENERATOR BATCH SUMMARY (%s)%45s║%n", mode.toUpperCase(), "");
        System.out.println("╠════════════╦══════════════════════╦═══════════╦═══════════╦═══════════╦══════════════╣");
        System.out.println("║ Level      ║ Question Type        ║ Generated ║  Skipped  ║  Imported ║ AlreadyExist ║");
        System.out.println("╠════════════╬══════════════════════╬═══════════╬═══════════╬═══════════╬══════════════╣");

        for (GeneratorRunResult r : results) {
            System.out.printf("║ HSK %-6s ║ %-20s ║ %9d ║ %9d ║ %9d ║ %12d ║%n",
                    r.getLevelCode(), r.getQuestionType(),
                    r.getGenerated(), r.getSkipped(), r.getImported(), r.getAlreadyExisted());
        }

        System.out.println("╠════════════╩══════════════════════╬═══════════╬═══════════╬═══════════╬══════════════╣");
        System.out.printf ("║ TOTAL (%2d batches)                ║ %9d ║ %9d ║ %9d ║ %12d ║%n",
                results.size(), totalGen, totalSkip, totalImp, totalExist);
        System.out.println("╚═══════════════════════════════════╩═══════════╩═══════════╩═══════════╩══════════════╝");

        if ("import".equals(mode) && autoPublish) {
            System.out.println();
            System.out.println(">>> Auto-publish enabled: Successfully published " + publishedCount + " questions to PUBLISHED status!");
        }
        System.out.println();
    }

    private static void printSingleResult(GeneratorRunResult r) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.printf ("║  Question Generator – %-27s║%n", r.getMode().toUpperCase());
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.printf ("║  Level       : %-34s║%n", r.getLevelCode());
        System.out.printf ("║  Type        : %-34s║%n", r.getQuestionType());
        System.out.printf ("║  Requested   : %-34d║%n", r.getRequestedLimit());
        System.out.printf ("║  Generated   : %-34d║%n", r.getGenerated());
        System.out.printf ("║  Skipped     : %-34d║%n", r.getSkipped());
        if ("import".equals(r.getMode())) {
            System.out.printf("║  Imported    : %-34d║%n", r.getImported());
            System.out.printf("║  Already existed: %-31d║%n", r.getAlreadyExisted());
        }
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println();

        List<GeneratedQuestionPreview> questions = r.getQuestions().stream()
                .filter(q -> q.getSkipReason() == null)
                .toList();

        int shown = Math.min(questions.size(), 3);
        for (int i = 0; i < shown; i++) {
            printQuestion(i + 1, questions.get(i));
        }
    }

    private static void printQuestion(int n, GeneratedQuestionPreview q) {
        System.out.printf("Q%d [%s | HSK %s | %s]%n", n, q.getQuestionType(), q.getLevelCode(), q.getValidationStatus());
        System.out.printf("   Prompt : %s%n", q.getPrompt());
        for (GeneratedQuestionPreview.ChoicePreview c : q.getChoices()) {
            System.out.printf("   %s. %s%s%n", c.getKey(), c.getText(), c.isCorrect() ? "  ✓" : "");
        }
        System.out.printf("   Answer : %s%n", q.getCorrectAnswer());
        System.out.printf("   Note   : %s%n", q.getExplanation());
        System.out.println();
    }

    private static String firstOr(ApplicationArguments args, String name, String def) {
        List<String> vals = args.getOptionValues(name);
        return (vals != null && !vals.isEmpty()) ? vals.get(0) : def;
    }
}
