package com.yibi.backend.service;

import com.yibi.backend.dto.placement.*;
import com.yibi.backend.entity.PlacementAssessment;
import com.yibi.backend.entity.PlacementResult;
import com.yibi.backend.entity.SkillAttempt;
import com.yibi.backend.entity.User;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.repository.PlacementAssessmentRepository;
import com.yibi.backend.repository.PlacementResultRepository;
import com.yibi.backend.repository.SkillAttemptRepository;
import com.yibi.backend.repository.UserRepository;
import com.yibi.backend.service.cat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Runs the adaptive placement test: picks each next question with {@link CatEngine}, grades answers,
 * and stores the attempt, its answers and its result. The question bank is read through JdbcTemplate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlacementService {

    private static final String SKILL_FILTER_WITH_SPEAKING =
            "'listening','reading','vocabulary','grammar','characters','writing','speaking'";
    private static final String SKILL_FILTER_WITHOUT_SPEAKING =
            "'listening','reading','vocabulary','grammar','characters','writing'";
    private static final Map<String, String> SKILL_LABELS = Map.of(
            "listening", "Nghe hiểu", "reading", "Đọc hiểu", "vocabulary", "Từ vựng", "grammar", "Ngữ pháp",
            "characters", "Chữ Hán", "writing", "Viết câu", "speaking", "Phát âm");

    private static final List<String> SKILL_ORDER =
            List.of("vocabulary", "characters", "grammar", "listening", "reading", "writing", "speaking");

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final PlacementAssessmentRepository assessmentRepository;
    private final SkillAttemptRepository skillAttemptRepository;
    private final PlacementResultRepository resultRepository;
    private final QuestionBankAdminService questionBankAdminService;
    private final SpeechScoringClient speechClient;
    private final PlacementOutcomeService outcomeService;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.speech.pass-accuracy:0.7}")
    private double speechPassAccuracy;

    @Transactional(readOnly = true)
    public PlacementStatusResponse getStatus(UUID userId) {
        User user = findUser(userId);
        return PlacementStatusResponse.builder()
                .onboardingCompleted(user.getOnboardingCompletedAt() != null)
                .startMode(user.getStartMode())
                .selfReportedLevel(user.getSelfReportedLevel())
                .hskLevel(user.getHskLevel())
                .inProgressAttemptId(assessmentRepository
                        .findFirstByUserIdAndModeAndStatusOrderByStartedAtDesc(userId, "ADAPTIVE", "IN_PROGRESS")
                        .map(PlacementAssessment::getId).orElse(null))
                .latestAttemptId(assessmentRepository
                        .findFirstByUserIdAndModeAndStatusOrderByStartedAtDesc(userId, "ADAPTIVE", "FINISHED")
                        .map(PlacementAssessment::getId).orElse(null))
                .poolReady(questionBankAdminService.getPoolCoverage().isReady())
                .speakingAvailable(speechClient.isAvailable())
                .build();
    }

    @Transactional
    public void saveSurvey(UUID userId, SurveyRequest request) {
        User user = findUser(userId);
        user.setSelfReportedLevel(request.getSelfLevel());
        user.setStartMode(request.getStartMode());
        if ("BASIC".equals(request.getStartMode())) {
            user.setHskLevel(1);
            user.setOnboardingCompletedAt(LocalDateTime.now());
        }
        userRepository.save(user);
    }

    /** Starts a test, or returns the unfinished one so a refresh does not lose progress. */
    @Transactional
    public PlacementStepResponse start(UUID userId) {
        Optional<PlacementAssessment> open = assessmentRepository
                .findFirstByUserIdAndModeAndStatusOrderByStartedAtDesc(userId, "ADAPTIVE", "IN_PROGRESS");
        if (open.isPresent()) {
            PlacementAssessment attempt = open.get();
            return step(attempt, pendingId(attempt), answeredCount(attempt.getId()), false);
        }
        User user = findUser(userId);
        List<CatItem> pool = loadPool(speechClient.isAvailable());
        CatConfig config = CatConfig.defaults();
        if (pool.size() < config.minQuestions()) {
            throw new BadRequestException("Ngân hàng câu hỏi chưa đủ câu đã duyệt để làm bài kiểm tra xếp trình độ.");
        }
        double priorMean = priorMean(user);
        CatEngine engine = new CatEngine(config, new Random());
        CatItem first = engine.nextItem(pool, List.of(), engine.estimate(List.of(), priorMean)).orElseThrow();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("priorMean", priorMean);
        snapshot.put("pending", first.id());
        snapshot.put("served", new ArrayList<>(List.of(first.id())));
        PlacementAssessment attempt = assessmentRepository.save(PlacementAssessment.builder()
                .user(user)
                .hskLevel(CatEngine.levelOf(priorMean))
                .questionSnapshot(snapshot)
                .totalQuestions(0)
                .mode("ADAPTIVE")
                .status("IN_PROGRESS")
                .algorithmVersion(CatEngine.ALGORITHM_VERSION)
                .build());
        if (user.getStartMode() == null) {
            user.setStartMode("PLACEMENT");
            userRepository.save(user);
        }
        return step(attempt, first.id(), 0, false);
    }

    @Transactional
    public PlacementStepResponse answer(UUID userId, Long attemptId, PlacementAnswerRequest request) {
        PlacementAssessment attempt = openAttempt(userId, attemptId);
        requirePending(attempt, request.getQuestionId());
        List<Map<String, Object>> choice = jdbc.queryForList(
                "SELECT id, is_correct FROM question_bank_choices WHERE question_id = ? AND choice_key = ?",
                request.getQuestionId(), request.getChoiceKey().trim());
        if (choice.isEmpty()) throw new BadRequestException("Lựa chọn không thuộc câu hỏi này.");
        boolean correct = Boolean.TRUE.equals(choice.get(0).get("is_correct"));
        long choiceId = ((Number) choice.get(0).get("id")).longValue();
        return recordAndAdvance(attempt, request.getQuestionId(), correct, choiceId, request.getResponseTimeSeconds());
    }

    /** Scores a recording of the sentence to read; the speech service call runs outside any transaction. */
    public PlacementStepResponse answerSpeaking(UUID userId, Long attemptId, Long questionId,
                                                MultipartFile audio, Integer responseTimeSeconds) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String target = tx.execute(status -> {
            PlacementAssessment attempt = openAttempt(userId, attemptId);
            requirePending(attempt, questionId);
            return readAloudTarget(questionId);
        });
        if (audio == null || audio.isEmpty()) throw new BadRequestException("Chưa có bản ghi âm.");
        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (IOException ex) {
            throw new BadRequestException("Không đọc được bản ghi âm.");
        }
        double accuracy = speechClient.score(bytes, audio.getOriginalFilename() != null ? audio.getOriginalFilename() : "speech.webm", target);
        boolean correct = accuracy >= speechPassAccuracy;
        return tx.execute(status -> recordAndAdvance(openAttempt(userId, attemptId), questionId, correct, null, responseTimeSeconds));
    }

    /** Lets the learner pass on a speaking question when the microphone or the speech service is unavailable. */
    @Transactional
    public PlacementStepResponse skipSpeaking(UUID userId, Long attemptId, Long questionId) {
        PlacementAssessment attempt = openAttempt(userId, attemptId);
        requirePending(attempt, questionId);
        readAloudTarget(questionId);
        return advance(attempt, attempt.getUser());
    }

    @Transactional(readOnly = true)
    public PlacementResultResponse getResult(UUID userId, Long attemptId) {
        PlacementResult result = resultRepository.findByAssessmentIdAndUserId(attemptId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Placement result", "attempt", attemptId));
        List<PlacementResultResponse.SkillScore> scores = new ArrayList<>();
        for (Map<String, Object> m : result.getSkillScores()) {
            scores.add(PlacementResultResponse.SkillScore.builder()
                    .skill(String.valueOf(m.get("skill"))).label(String.valueOf(m.get("label")))
                    .attempts(((Number) m.get("attempts")).intValue()).correct(((Number) m.get("correct")).intValue())
                    .theta(((Number) m.get("theta")).doubleValue()).level(((Number) m.get("level")).intValue())
                    .build());
        }
        List<PlacementResultResponse.MissedWord> missed = missedWords(attemptId);
        return PlacementResultResponse.builder()
                .attemptId(attemptId).level(result.getEstimatedLevel()).confidence(result.getConfidence())
                .theta(result.getTheta().doubleValue()).standardError(result.getStdError().doubleValue())
                .questionCount(result.getQuestionCount())
                .recommendedLevel(result.getRecommendedLevel() != null ? result.getRecommendedLevel() : result.getEstimatedLevel())
                .skillScores(scores).strengths(result.getStrengths()).weaknesses(result.getWeaknesses())
                .weakTopics(result.getWeakTopics())
                .recommendedCourseId(result.getRecommendedCourseId())
                .recommendedCourseTitle(courseTitle(result.getRecommendedCourseId()))
                .missedWords(missed)
                .reviewCardsAdded((int) missed.stream().filter(PlacementResultResponse.MissedWord::isInDeck).count())
                .build();
    }

    private String courseTitle(Long courseId) {
        if (courseId == null) return null;
        List<String> titles = jdbc.queryForList("SELECT title FROM courses WHERE id = ?", String.class, courseId);
        return titles.isEmpty() ? null : titles.get(0);
    }

    private List<PlacementResultResponse.MissedWord> missedWords(Long attemptId) {
        return jdbc.query("""
                SELECT DISTINCT vr.simplified, rd.pinyin,
                       EXISTS (SELECT 1 FROM srs_cards c
                               WHERE c.user_id = a.user_id AND c.hsk_vocabulary_record_id = vr.raw_record_id) AS in_deck
                FROM skill_attempts a
                JOIN question_bank_sources s ON s.question_id = a.question_id
                JOIN hsk_vocabulary_records vr ON vr.raw_record_id = s.raw_record_id
                JOIN hsk_vocabulary_readings rd ON rd.raw_record_id = vr.raw_record_id AND rd.reading_order = 1
                WHERE a.assessment_id = ? AND a.is_correct = FALSE
                ORDER BY vr.simplified
                """, (rs, i) -> new PlacementResultResponse.MissedWord(rs.getString("simplified"),
                        rs.getString("pinyin"), rs.getBoolean("in_deck")), attemptId);
    }

    // ── flow ────────────────────────────────────────────────────────────────

    private PlacementStepResponse recordAndAdvance(PlacementAssessment attempt, Long questionId, boolean correct,
                                                   Long choiceId, Integer responseTimeSeconds) {
        List<SkillAttempt> answered = skillAttemptRepository.findByAssessmentIdOrderByDisplayOrderAsc(attempt.getId());
        double priorMean = priorMean(attempt);
        CatEngine engine = new CatEngine(CatConfig.defaults(), new Random());
        Map<Long, CatItem> items = loadItems(idsOf(answered, questionId));
        CatItem item = items.get(questionId);
        double thetaBefore = engine.estimate(toResponses(answered, items), priorMean).theta();
        skillAttemptRepository.save(SkillAttempt.builder()
                .assessment(attempt).user(attempt.getUser())
                .questionKey(String.valueOf(questionId)).skillKey(item.skill()).topicKey(item.topic())
                .correct(correct).responseTimeSeconds(responseTimeSeconds != null ? responseTimeSeconds : 0)
                .questionId(questionId).selectedChoiceId(choiceId)
                .displayOrder(answered.size() + 1).thetaBefore(BigDecimal.valueOf(thetaBefore).setScale(3, RoundingMode.HALF_UP))
                .build());
        skillAttemptRepository.flush();
        return advance(attempt, attempt.getUser());
    }

    /** Estimates ability from the answers so far, then ends the test or picks the next question. */
    private PlacementStepResponse advance(PlacementAssessment attempt, User user) {
        List<SkillAttempt> answered = skillAttemptRepository.findByAssessmentIdOrderByDisplayOrderAsc(attempt.getId());
        Map<String, Object> snapshot = new LinkedHashMap<>(attempt.getQuestionSnapshot());
        List<Long> served = new ArrayList<>();
        for (Object o : (List<?>) snapshot.getOrDefault("served", List.of())) served.add(((Number) o).longValue());
        Set<Long> answeredIds = new HashSet<>();
        answered.forEach(a -> answeredIds.add(a.getQuestionId()));
        Set<Long> skipped = new HashSet<>(served);
        skipped.removeAll(answeredIds);
        Long pending = pendingId(attempt);
        if (pending != null && !answeredIds.contains(pending)) skipped.add(pending);

        CatConfig config = CatConfig.defaults();
        CatEngine engine = new CatEngine(config, new Random());
        double priorMean = priorMean(attempt);
        Map<Long, CatItem> answeredItems = loadItems(answeredIds);
        List<CatResponse> responses = toResponses(answered, answeredItems);
        CatEstimate estimate = engine.estimate(responses, priorMean);

        List<CatItem> pool = new ArrayList<>(loadPool(speechClient.isAvailable()));
        pool.removeIf(item -> skipped.contains(item.id()));
        // Only speaking questions can be skipped, and a learner who passes once is not asked to speak again.
        if (!skipped.isEmpty()) pool.removeIf(item -> "speaking".equals(item.skill()));
        if (engine.shouldStop(responses, estimate, pool)) {
            return finish(attempt, user, engine, responses, estimate, priorMean);
        }
        Optional<CatItem> next = engine.nextItem(pool, responses, estimate);
        if (next.isEmpty()) {
            return finish(attempt, user, engine, responses, estimate, priorMean);
        }
        served.add(next.get().id());
        snapshot.put("served", served);
        snapshot.put("pending", next.get().id());
        attempt.setQuestionSnapshot(snapshot);
        attempt.setTotalQuestions(answered.size());
        assessmentRepository.save(attempt);
        return step(attempt, next.get().id(), answered.size(), false);
    }

    private PlacementStepResponse finish(PlacementAssessment attempt, User user, CatEngine engine,
                                         List<CatResponse> responses, CatEstimate estimate, double priorMean) {
        CatResult result = engine.finish(responses, estimate, priorMean);
        int correctCount = (int) responses.stream().filter(CatResponse::correct).count();
        Map<String, Object> snapshot = new LinkedHashMap<>(attempt.getQuestionSnapshot());
        snapshot.put("pending", null);
        attempt.setQuestionSnapshot(snapshot);
        attempt.setStatus("FINISHED");
        attempt.setFinishedAt(LocalDateTime.now());
        attempt.setTheta(scaled(result.theta()));
        attempt.setStdError(scaled(result.standardError()));
        attempt.setEstimatedLevel(result.level());
        attempt.setConfidence(result.confidence().name());
        attempt.setAlgorithmVersion(CatEngine.ALGORITHM_VERSION);
        attempt.setHskLevel(result.level());
        attempt.setTotalQuestions(responses.size());
        attempt.setCorrectCount(correctCount);
        attempt.setScorePercent(BigDecimal.valueOf(responses.isEmpty() ? 0 : correctCount * 100.0 / responses.size())
                .setScale(2, RoundingMode.HALF_UP));
        assessmentRepository.save(attempt);

        List<Map<String, Object>> skillScores = new ArrayList<>();
        for (CatSkillScore s : result.skillScores()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skill", s.skill());
            m.put("label", SKILL_LABELS.getOrDefault(s.skill(), s.skill()));
            m.put("attempts", s.attempts());
            m.put("correct", s.correct());
            m.put("theta", Math.round(s.theta() * 100) / 100.0);
            m.put("level", CatEngine.levelOf(s.theta()));
            skillScores.add(m);
        }
        PlacementResult stored = PlacementResult.builder()
                .assessmentId(attempt.getId()).user(user)
                .estimatedLevel(result.level()).confidence(result.confidence().name())
                .theta(scaled(result.theta())).stdError(scaled(result.standardError()))
                .questionCount(responses.size())
                .skillScores(skillScores).strengths(result.strengths()).weaknesses(result.weaknesses())
                .weakTopics(result.weakTopics()).recommendedLevel(result.level())
                .build();
        outcomeService.apply(user, attempt, stored,
                skillAttemptRepository.findByAssessmentIdOrderByDisplayOrderAsc(attempt.getId()));
        resultRepository.save(stored);

        user.setHskLevel(result.level());
        user.setOnboardingCompletedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("Placement attempt {} finished: level={} confidence={} questions={}",
                attempt.getId(), result.level(), result.confidence(), responses.size());
        return PlacementStepResponse.builder()
                .attemptId(attempt.getId()).finished(true).answered(responses.size())
                .minimumQuestions(CatConfig.defaults().minQuestions()).maximumQuestions(CatConfig.defaults().maxQuestions())
                .skillProgress(skillProgress(attempt.getId()))
                .build();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private PlacementStepResponse step(PlacementAssessment attempt, Long questionId, int answered, boolean finished) {
        CatConfig config = CatConfig.defaults();
        return PlacementStepResponse.builder()
                .attemptId(attempt.getId()).finished(finished)
                .question(questionId != null ? loadQuestion(questionId, answered + 1) : null)
                .answered(answered).minimumQuestions(config.minQuestions()).maximumQuestions(config.maxQuestions())
                .skillProgress(skillProgress(attempt.getId()))
                .build();
    }

    private List<PlacementStepResponse.SkillProgress> skillProgress(Long attemptId) {
        Map<String, Integer> counts = new HashMap<>();
        for (SkillAttempt a : skillAttemptRepository.findByAssessmentIdOrderByDisplayOrderAsc(attemptId)) {
            counts.merge(a.getSkillKey(), 1, Integer::sum);
        }
        List<PlacementStepResponse.SkillProgress> progress = new ArrayList<>();
        for (String skill : SKILL_ORDER) {
            progress.add(new PlacementStepResponse.SkillProgress(skill, SKILL_LABELS.getOrDefault(skill, skill), counts.getOrDefault(skill, 0)));
        }
        return progress;
    }

    private PlacementQuestionDto loadQuestion(Long id, int order) {
        Map<String, Object> q = jdbc.queryForMap(
                "SELECT question_type, skill_key, prompt, prompt_pinyin, prompt_audio_url FROM question_bank_items WHERE id = ?", id);
        List<PlacementQuestionDto.Choice> choices = jdbc.query(
                "SELECT choice_key, choice_text FROM question_bank_choices WHERE question_id = ? ORDER BY choice_order",
                (rs, i) -> new PlacementQuestionDto.Choice(rs.getString("choice_key"), rs.getString("choice_text")), id);
        return PlacementQuestionDto.builder()
                .id(id).skill((String) q.get("skill_key")).type((String) q.get("question_type"))
                .prompt((String) q.get("prompt")).promptPinyin((String) q.get("prompt_pinyin"))
                .audioUrl((String) q.get("prompt_audio_url")).choices(choices).order(order).build();
    }

    private String readAloudTarget(Long questionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT question_type, answer_text FROM question_bank_items WHERE id = ?", questionId);
        if (rows.isEmpty() || !"READ_ALOUD".equals(rows.get(0).get("question_type"))) {
            throw new BadRequestException("Câu này không phải câu đọc thành tiếng.");
        }
        return (String) rows.get(0).get("answer_text");
    }

    private List<CatItem> loadPool(boolean withSpeaking) {
        return jdbc.query("""
                SELECT q.id, q.skill_key, q.topic_key, c.irt_a, c.irt_b
                FROM question_bank_items q JOIN question_calibration c ON c.question_id = q.id
                WHERE q.status = 'PUBLISHED' AND q.standard_code = 'NEW_HSK' AND q.version_code = '2025'
                  AND q.skill_key IN (%s)
                """.formatted(withSpeaking ? SKILL_FILTER_WITH_SPEAKING : SKILL_FILTER_WITHOUT_SPEAKING),
                (rs, i) -> new CatItem(rs.getLong("id"), rs.getString("skill_key"), rs.getString("topic_key"),
                        rs.getDouble("irt_a"), rs.getDouble("irt_b")));
    }

    /** Questions that were answered stay measurable even if they were deactivated mid-test. */
    private Map<Long, CatItem> loadItems(Collection<Long> ids) {
        Map<Long, CatItem> items = new HashMap<>();
        if (ids.isEmpty()) return items;
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbc.query("""
                SELECT q.id, q.skill_key, q.topic_key, COALESCE(c.irt_a, 1.0) AS a, COALESCE(c.irt_b, 3.0) AS b
                FROM question_bank_items q LEFT JOIN question_calibration c ON c.question_id = q.id
                WHERE q.id IN (%s)
                """.formatted(in), rs -> {
            items.put(rs.getLong("id"), new CatItem(rs.getLong("id"), rs.getString("skill_key"),
                    rs.getString("topic_key"), rs.getDouble("a"), rs.getDouble("b")));
        }, ids.toArray());
        return items;
    }

    private List<CatResponse> toResponses(List<SkillAttempt> answered, Map<Long, CatItem> items) {
        List<CatResponse> responses = new ArrayList<>();
        for (SkillAttempt a : answered) {
            CatItem item = items.get(a.getQuestionId());
            if (item != null) responses.add(new CatResponse(item, Boolean.TRUE.equals(a.getCorrect())));
        }
        return responses;
    }

    private Set<Long> idsOf(List<SkillAttempt> answered, Long extra) {
        Set<Long> ids = new HashSet<>();
        answered.forEach(a -> ids.add(a.getQuestionId()));
        ids.add(extra);
        return ids;
    }

    private PlacementAssessment openAttempt(UUID userId, Long attemptId) {
        PlacementAssessment attempt = assessmentRepository.findByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Placement attempt", "id", attemptId));
        if (!"ADAPTIVE".equals(attempt.getMode()) || !"IN_PROGRESS".equals(attempt.getStatus())) {
            throw new BadRequestException("Bài kiểm tra này đã kết thúc.");
        }
        return attempt;
    }

    private void requirePending(PlacementAssessment attempt, Long questionId) {
        Long pending = pendingId(attempt);
        if (pending == null || !pending.equals(questionId)) {
            throw new BadRequestException("Đây không phải câu hỏi hiện tại của bài kiểm tra.");
        }
    }

    private Long pendingId(PlacementAssessment attempt) {
        Object pending = attempt.getQuestionSnapshot().get("pending");
        return pending instanceof Number number ? number.longValue() : null;
    }

    private int answeredCount(Long attemptId) {
        return skillAttemptRepository.findByAssessmentIdOrderByDisplayOrderAsc(attemptId).size();
    }

    private double priorMean(PlacementAssessment attempt) {
        Object stored = attempt.getQuestionSnapshot().get("priorMean");
        return stored instanceof Number number ? number.doubleValue() : 3.0;
    }

    /** Where the test starts looking: the level the learner said they had, or a low guess when they said none. */
    private double priorMean(User user) {
        Integer self = user.getSelfReportedLevel();
        if (self == null) return 3.0;
        if (self == 0) return 1.5;
        return self >= 7 ? 8.0 : self;
    }

    private static BigDecimal scaled(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }
}
