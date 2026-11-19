package com.yibi.backend.controller.srs;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.srs.SrsCardResponse;
import com.yibi.backend.dto.srs.SrsRateRequest;
import com.yibi.backend.dto.srs.SrsReviewRequest;
import com.yibi.backend.dto.srs.SrsStatsResponse;
import com.yibi.backend.dto.srs.SrsAddLessonResponse;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.dto.srs.SrsWritingCardResponse;
import com.yibi.backend.dto.srs.SrsWritingRateRequest;
import com.yibi.backend.service.SrsService;
import com.yibi.backend.service.SrsWritingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/srs")
@RequiredArgsConstructor
public class SrsController {

    private final SrsService srsService;
    private final SrsWritingService srsWritingService;
    private final com.yibi.backend.service.WordDetailsService wordDetailsService;
    private final SecurityUtils securityUtils;
    private final com.yibi.backend.service.LessonAccessService lessonAccess;

    /** Cards due today; with deckId, only the words of that deck of the learner. */
    @GetMapping("/due")
    public ResponseEntity<ApiResponse<List<SrsCardResponse>>> getDueCards(@RequestParam(required = false) Long deckId) {
        UUID userId = securityUtils.getCurrentUserId();
        List<SrsCardResponse> dueCards = deckId == null ? srsService.getDueCards(userId) : srsService.getDueCards(userId, deckId);
        return ResponseEntity.ok(ApiResponse.success(dueCards));
    }

    /** The learner's flashcard for a word (null if none) and their decks, marking those holding it. */
    @GetMapping("/words/{vocabularyId}")
    public ResponseEntity<ApiResponse<com.yibi.backend.dto.vocabulary.WordDetailsDtos.WordStudyResponse>> getWordStudy(
            @PathVariable Long vocabularyId) {
        return ResponseEntity.ok(ApiResponse.success(wordDetailsService.study(securityUtils.getCurrentUserId(), vocabularyId)));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<SrsStatsResponse>> getStats() {
        UUID userId = securityUtils.getCurrentUserId();
        SrsStatsResponse stats = srsService.getSrsStats(userId);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @PostMapping("/add/{vocabularyId}")
    public ResponseEntity<ApiResponse<SrsCardResponse>> addCard(@PathVariable Long vocabularyId) {
        UUID userId = securityUtils.getCurrentUserId();
        SrsCardResponse card = srsService.addCard(userId, vocabularyId);
        return ResponseEntity.ok(ApiResponse.success("Card added to your SRS deck", card));
    }

    @PostMapping("/add-lesson/{lessonId}")
    public ResponseEntity<ApiResponse<SrsAddLessonResponse>> addLessonVocabulary(@PathVariable Long lessonId) {
        lessonAccess.requireLesson(lessonId);
        UUID userId = securityUtils.getCurrentUserId();
        SrsAddLessonResponse result = srsService.addLessonVocabulary(userId, lessonId);
        return ResponseEntity.ok(ApiResponse.success("Lesson vocabulary added to your SRS deck", result));
    }

    @PostMapping("/review")
    public ResponseEntity<ApiResponse<SrsCardResponse>> reviewCard(@Valid @RequestBody SrsReviewRequest request) {
        UUID userId = securityUtils.getCurrentUserId();
        SrsCardResponse card = srsService.reviewCard(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Review recorded", card));
    }

    /** Four-button rating from topic flashcards; adds the word to the deck if needed. */
    @PostMapping("/rate")
    public ResponseEntity<ApiResponse<SrsCardResponse>> rateVocabulary(@Valid @RequestBody SrsRateRequest request) {
        UUID userId = securityUtils.getCurrentUserId();
        SrsCardResponse card = srsService.rateVocabulary(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Rating recorded", card));
    }

    /** Written characters due for review today. */
    @GetMapping("/writing/due")
    public ResponseEntity<ApiResponse<List<SrsWritingCardResponse>>> getDueWritingCards() {
        UUID userId = securityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(srsWritingService.getDueCards(userId)));
    }

    /** Four-button rating of a written character; creates its card on the first rating. */
    @PostMapping("/writing/rate")
    public ResponseEntity<ApiResponse<SrsWritingCardResponse>> rateWriting(@Valid @RequestBody SrsWritingRateRequest request) {
        UUID userId = securityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success("Rating recorded", srsWritingService.rate(userId, request)));
    }
}
