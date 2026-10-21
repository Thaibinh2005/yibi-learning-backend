package com.yibi.backend.service;

import com.yibi.backend.dto.srs.SrsCardResponse;
import com.yibi.backend.dto.srs.SrsReviewRequest;
import com.yibi.backend.dto.srs.SrsStatsResponse;
import com.yibi.backend.entity.SrsCard;
import com.yibi.backend.entity.User;
import com.yibi.backend.entity.Vocabulary;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.repository.SrsCardRepository;
import com.yibi.backend.repository.UserRepository;
import com.yibi.backend.repository.VocabularyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SrsService {

    private final SrsCardRepository srsCardRepository;
    private final UserRepository userRepository;
    private final VocabularyRepository vocabularyRepository;

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
        if (srsCardRepository.existsByUserIdAndVocabularyId(userId, vocabularyId)) {
            throw new BadRequestException("This vocabulary word is already in your SRS flashcard deck");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        Vocabulary vocabulary = vocabularyRepository.findById(vocabularyId)
                .orElseThrow(() -> new ResourceNotFoundException("Vocabulary", "id", vocabularyId));

        SrsCard card = SrsCard.builder()
                .user(user)
                .vocabulary(vocabulary)
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
        Vocabulary v = card.getVocabulary();
        return SrsCardResponse.builder()
                .cardId(card.getId())
                .vocabularyId(v != null ? v.getId() : null)
                .hanzi(v != null ? v.getHanzi() : null)
                .hanziTraditional(v != null ? v.getHanziTraditional() : null)
                .pinyin(v != null ? v.getPinyin() : null)
                .meaningVi(v != null ? v.getMeaningVi() : null)
                .meaningEn(v != null ? v.getMeaningEn() : null)
                .hskLevel(v != null ? v.getHskLevel() : null)
                .partOfSpeech(v != null ? v.getPartOfSpeech() : null)
                .exampleSentence(v != null ? v.getExampleSentence() : null)
                .examplePinyin(v != null ? v.getExamplePinyin() : null)
                .audioUrl(v != null ? v.getAudioUrl() : null)
                .repetition(card.getRepetitions())
                .intervalDays(card.getIntervalDays())
                .easeFactor(card.getEaseFactor())
                .dueDate(card.getDueDate())
                .lastReviewed(card.getLastReviewed())
                .build();
    }
}
