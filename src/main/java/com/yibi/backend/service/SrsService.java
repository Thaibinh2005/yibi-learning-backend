package com.yibi.backend.service;

import com.yibi.backend.dto.srs.SrsCardResponse;
import com.yibi.backend.dto.srs.SrsRateRequest;
import com.yibi.backend.dto.srs.SrsReviewRequest;
import com.yibi.backend.dto.srs.SrsStatsResponse;
import com.yibi.backend.dto.srs.SrsAddLessonResponse;
import com.yibi.backend.entity.Lesson;
import com.yibi.backend.entity.SrsCard;
import com.yibi.backend.entity.User;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.repository.SrsCardRepository;
import com.yibi.backend.repository.LessonRepository;
import com.yibi.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SrsService {

    private final SrsCardRepository srsCardRepository;
    private final UserRepository userRepository;
    private final VocabularyService vocabularyService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final LessonRepository lessonRepository;

    private static final BigDecimal MIN_EASE_FACTOR = new BigDecimal("1.30");
    private static final BigDecimal DEFAULT_EASE_FACTOR = new BigDecimal("2.50");

    @Transactional(readOnly = true)
    public List<SrsCardResponse> getDueCards(UUID userId) {
        LocalDate today = LocalDate.now();
        List<SrsCard> cards = srsCardRepository.findDueCards(userId, today);
        return cards.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SrsStatsResponse getSrsStats(UUID userId) {
        LocalDate today = LocalDate.now();
        long dueCount = srsCardRepository.countDueCards(userId, today);
        List<SrsCard> allUserCards = srsCardRepository.findByUserId(userId);

        long total = allUserCards.size();
        long mastered = allUserCards.stream().filter(c -> c.getRepetitions() != null && c.getRepetitions() >= 3).count();
        long learning = total - mastered;

        return SrsStatsResponse.builder()
                .totalCards(total)
                .dueToday(dueCount)
                .learningCards(learning)
                .masteredCards(mastered)
                .build();
    }

    @Transactional
    public SrsCardResponse addCard(UUID userId, Long vocabularyId) {
        if (srsCardRepository.existsByUserIdAndHskVocabularyRecordId(userId, vocabularyId)) {
            throw new BadRequestException("This vocabulary word is already in your SRS flashcard deck");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        vocabularyService.getVocabularyById(vocabularyId);

        SrsCard card = SrsCard.builder()
                .user(user)
                .hskVocabularyRecordId(vocabularyId)
                .repetitions(0)
                .intervalDays(1)
                .easeFactor(DEFAULT_EASE_FACTOR)
                .dueDate(LocalDate.now())
                .build();

        SrsCard saved = srsCardRepository.save(card);
        log.info("Added card to SRS: userId={}, vocabularyId={}, cardId={}", userId, vocabularyId, saved.getId());
        return mapToResponse(saved);
    }

    @Transactional
    public SrsAddLessonResponse addLessonVocabulary(UUID userId, Long lessonId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", lessonId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Map<String, Object> payload = lesson.getLessonPayload();
        Map<?, ?> sections = payload != null && payload.get("sections") instanceof Map<?, ?> sectionMap
                ? sectionMap : Map.of();
        List<?> words = sections.get("vocabulary") instanceof List<?> list ? list : List.of();
        int added = 0;
        int alreadyInDeck = 0;
        int unavailable = 0;

        for (Object rawWord : words) {
            if (!(rawWord instanceof Map<?, ?> word)) {
                unavailable++;
                continue;
            }
            String hanzi = word.get("hanzi") == null ? "" : String.valueOf(word.get("hanzi")).trim();
            String pinyin = word.get("pinyin") == null ? "" : String.valueOf(word.get("pinyin")).trim();
            if (hanzi.isBlank() || pinyin.isBlank()) {
                unavailable++;
                continue;
            }
            List<Long> matches = jdbc.queryForList("""
                    SELECT DISTINCT v.raw_record_id
                    FROM hsk_vocabulary_records v
                    JOIN hsk_vocabulary_readings r ON r.raw_record_id = v.raw_record_id
                    WHERE v.simplified = ? AND r.pinyin = ? AND v.level_code = ?
                    ORDER BY v.raw_record_id LIMIT 2
                    """, Long.class, hanzi, pinyin, String.valueOf(lesson.getCourse().getHskLevel()));
            // Exact Hanzi + Pinyin + level is still ambiguous when the source has
            // multiple records; do not silently collapse distinct source entries.
            if (matches.size() != 1) {
                unavailable++;
                continue;
            }
            Long vocabularyId = matches.get(0);
            if (srsCardRepository.existsByUserIdAndHskVocabularyRecordId(userId, vocabularyId)) {
                alreadyInDeck++;
                continue;
            }
            SrsCard card = SrsCard.builder()
                    .user(user)
                    .hskVocabularyRecordId(vocabularyId)
                    .repetitions(0)
                    .intervalDays(1)
                    .easeFactor(DEFAULT_EASE_FACTOR)
                    .dueDate(LocalDate.now())
                    .build();
            srsCardRepository.save(card);
            added++;
        }

        return SrsAddLessonResponse.builder()
                .added(added)
                .alreadyInDeck(alreadyInDeck)
                .unavailable(unavailable)
                .build();
    }

    /** Result of the four-button scheduler: next interval (0 = again today) and ease factor. */
    record RatingSchedule(int intervalDays, int repetitions, BigDecimal easeFactor) {}

    /**
     * Four-button scheduler used by topic flashcards.
     * Intervals grow with the card's history but stay inside fixed bounds:
     * AGAIN = 0 days (re-shown after ~10 minutes in the session), HARD = 1-3,
     * GOOD = 1-4, EASY = 1-7 days. EASY is always longer than GOOD, and GOOD
     * never shorter than HARD.
     */
    static RatingSchedule scheduleRating(SrsRateRequest.Rating rating, int repetitions, int intervalDays,
                                         BigDecimal easeFactor) {
        double ef = easeFactor.doubleValue();
        int base = Math.max(intervalDays, 1);

        int hard = clamp((int) Math.round(base * 1.2), 1, 3);
        int good = repetitions == 0 ? 1 : clamp((int) Math.round(base * ef), 1, 4);
        good = Math.max(good, hard);
        int easy = clamp(Math.max((int) Math.round(base * ef * 1.3), good + 1), 1, 7);

        return switch (rating) {
            case AGAIN -> new RatingSchedule(0, 0, adjustEase(easeFactor, -0.20));
            case HARD -> new RatingSchedule(hard, repetitions + 1, adjustEase(easeFactor, -0.15));
            case GOOD -> new RatingSchedule(good, repetitions + 1, easeFactor);
            case EASY -> new RatingSchedule(easy, repetitions + 1, adjustEase(easeFactor, 0.15));
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static BigDecimal adjustEase(BigDecimal ef, double delta) {
        BigDecimal next = ef.add(BigDecimal.valueOf(delta)).setScale(2, RoundingMode.HALF_UP);
        return next.compareTo(MIN_EASE_FACTOR) < 0 ? MIN_EASE_FACTOR : next;
    }

    @Transactional
    public SrsCardResponse rateVocabulary(UUID userId, SrsRateRequest request) {
        Long vocabularyId = request.getVocabularyId();
        // Topic lists de-duplicate words by Hanzi but may pick a different source
        // record per topic/level; reuse the user's existing card for the same word
        // so one word never ends up as several cards in the deck.
        List<Long> existing = jdbc.queryForList("""
                SELECT c.id
                FROM srs_cards c
                JOIN hsk_vocabulary_records cv ON cv.raw_record_id = c.hsk_vocabulary_record_id
                JOIN hsk_vocabulary_records target ON target.raw_record_id = ?
                WHERE c.user_id = ? AND cv.simplified = target.simplified
                ORDER BY (c.hsk_vocabulary_record_id = ?) DESC, c.id
                LIMIT 1
                """, Long.class, vocabularyId, userId, vocabularyId);
        SrsCard card = (existing.isEmpty()
                ? srsCardRepository.findByUserIdAndHskVocabularyRecordId(userId, vocabularyId)
                : srsCardRepository.findById(existing.get(0)))
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
                    vocabularyService.getVocabularyById(vocabularyId);
                    return SrsCard.builder()
                            .user(user)
                            .hskVocabularyRecordId(vocabularyId)
                            .repetitions(0)
                            .intervalDays(1)
                            .easeFactor(DEFAULT_EASE_FACTOR)
                            .dueDate(LocalDate.now())
                            .build();
                });

        RatingSchedule next = scheduleRating(
                request.getRating(),
                card.getRepetitions() != null ? card.getRepetitions() : 0,
                card.getIntervalDays() != null ? card.getIntervalDays() : 1,
                card.getEaseFactor() != null ? card.getEaseFactor() : DEFAULT_EASE_FACTOR);

        card.setRepetitions(next.repetitions());
        card.setIntervalDays(next.intervalDays());
        card.setEaseFactor(next.easeFactor());
        card.setLastReviewed(LocalDateTime.now());
        card.setDueDate(LocalDate.now().plusDays(next.intervalDays()));

        SrsCard saved = srsCardRepository.save(card);
        log.info("SRS Rate: userId={}, vocabularyId={}, rating={}, interval={}d",
                userId, vocabularyId, request.getRating(), next.intervalDays());
        return mapToResponse(saved);
    }

    @Transactional
    public SrsCardResponse reviewCard(UUID userId, SrsReviewRequest request) {
        SrsCard card = srsCardRepository.findById(request.getCardId())
                .orElseThrow(() -> new ResourceNotFoundException("SrsCard", "id", request.getCardId()));

        if (!card.getUser().getId().equals(userId)) {
            throw new BadRequestException("You do not own this flashcard");
        }

        int grade = request.getGrade();
        int repetition = card.getRepetitions() != null ? card.getRepetitions() : 0;
        int intervalDays = card.getIntervalDays() != null ? card.getIntervalDays() : 1;
        BigDecimal easeFactor = card.getEaseFactor() != null ? card.getEaseFactor() : DEFAULT_EASE_FACTOR;

        // ── SM-2 Algorithm Implementation ──────────────────────────
        // 1. Calculate new Ease Factor: EF' = EF + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))
        double qFactor = 5.0 - grade;
        double efDelta = 0.1 - (qFactor * (0.08 + (qFactor * 0.02)));
        BigDecimal newEaseFactor = easeFactor.add(BigDecimal.valueOf(efDelta)).setScale(2, RoundingMode.HALF_UP);
        if (newEaseFactor.compareTo(MIN_EASE_FACTOR) < 0) {
            newEaseFactor = MIN_EASE_FACTOR;
        }

        // 2. Calculate new interval & repetition
        int newIntervalDays;
        int newRepetition;

        if (grade < 3) {
            // Failed recall: reset repetition to 0, review again tomorrow
            newRepetition = 0;
            newIntervalDays = 1;
        } else {
            // Successful recall
            if (repetition == 0) {
                newIntervalDays = 1;
            } else if (repetition == 1) {
                newIntervalDays = 6;
            } else {
                newIntervalDays = (int) Math.round(intervalDays * newEaseFactor.doubleValue());
            }
            newRepetition = repetition + 1;
        }

        LocalDate today = LocalDate.now();
        card.setRepetitions(newRepetition);
        card.setIntervalDays(newIntervalDays);
        card.setEaseFactor(newEaseFactor);
        card.setLastReviewed(LocalDateTime.now());
        card.setDueDate(today.plusDays(newIntervalDays));

        SrsCard updated = srsCardRepository.save(card);
        log.info("SRS Review: cardId={}, grade={}, newRepetition={}, newInterval={}d, newEF={}",
                card.getId(), grade, newRepetition, newIntervalDays, newEaseFactor);

        return mapToResponse(updated);
    }

    private SrsCardResponse mapToResponse(SrsCard card) {
        var v = vocabularyService.getVocabularyById(card.getHskVocabularyRecordId());
        return SrsCardResponse.builder()
                .cardId(card.getId())
                .vocabularyId(v.getId())
                .hanzi(v.getHanzi())
                .hanziTraditional(v.getHanziTraditional())
                .pinyin(v.getPinyin())
                .meaningVi(v.getMeaningVi())
                .meaningEn(v.getMeaningEn())
                .hskLevel(v.getHskLevel())
                .levelCode(v.getLevelCode())
                .partOfSpeech(v.getPartOfSpeech())
                .exampleSentence(v.getExampleSentence())
                .examplePinyin(v.getExamplePinyin())
                .exampleTranslation(v.getExampleTranslation())
                .exampleAudioUrl(v.getExampleAudioUrl())
                .measureWords(v.getMeasureWords())
                .repetition(card.getRepetitions())
                .intervalDays(card.getIntervalDays())
                .easeFactor(card.getEaseFactor())
                .dueDate(card.getDueDate())
                .lastReviewed(card.getLastReviewed())
                .build();
    }
}
