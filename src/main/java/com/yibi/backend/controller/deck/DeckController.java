package com.yibi.backend.controller.deck;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.deck.DeckDtos.DeckChangeResult;
import com.yibi.backend.dto.deck.DeckDtos.DeckNameRequest;
import com.yibi.backend.dto.deck.DeckDtos.DeckResponse;
import com.yibi.backend.dto.deck.DeckDtos.DeckTransferRequest;
import com.yibi.backend.dto.deck.DeckDtos.DeckWordPage;
import com.yibi.backend.dto.deck.DeckDtos.DeckWordsRequest;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.VocabDeckService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The learner's own vocabulary decks; every call needs a signed-in learner and touches only their decks. */
@RestController
@RequestMapping("/api/decks")
@RequiredArgsConstructor
public class DeckController {

    private final VocabDeckService decks;
    private final SecurityUtils securityUtils;

    @GetMapping
    public ResponseEntity<ApiResponse<List<DeckResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(decks.listDecks(securityUtils.getCurrentUserId())));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DeckResponse>> create(@Valid @RequestBody DeckNameRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã tạo bộ từ vựng",
                decks.createDeck(securityUtils.getCurrentUserId(), request.name())));
    }

    @PatchMapping("/{deckId}")
    public ResponseEntity<ApiResponse<DeckResponse>> rename(@PathVariable long deckId, @Valid @RequestBody DeckNameRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã đổi tên bộ từ vựng",
                decks.renameDeck(securityUtils.getCurrentUserId(), deckId, request.name())));
    }

    @DeleteMapping("/{deckId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable long deckId) {
        decks.deleteDeck(securityUtils.getCurrentUserId(), deckId);
        return ResponseEntity.ok(ApiResponse.success("Đã xoá bộ từ vựng", null));
    }

    /**
     * The words of a deck. search: chữ Hán, pinyin or Vietnamese meaning; level: 1-6 or 7-9; topic: topic slug;
     * status: NEW, LEARNING, MASTERED, LEARNED or DUE; sort: added, reviewed or memory; direction: asc or desc.
     */
    @GetMapping("/{deckId}/words")
    public ResponseEntity<ApiResponse<DeckWordPage>> words(
            @PathVariable long deckId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var query = new VocabDeckService.WordQuery(search, level, topic, status, sort, direction, page, size);
        return ResponseEntity.ok(ApiResponse.success(decks.words(securityUtils.getCurrentUserId(), deckId, query)));
    }

    @PostMapping("/{deckId}/words")
    public ResponseEntity<ApiResponse<DeckChangeResult>> addWords(@PathVariable long deckId, @Valid @RequestBody DeckWordsRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                decks.addWords(securityUtils.getCurrentUserId(), deckId, request.vocabularyIds())));
    }

    @PostMapping("/{deckId}/words/remove")
    public ResponseEntity<ApiResponse<DeckChangeResult>> removeWords(@PathVariable long deckId, @Valid @RequestBody DeckWordsRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                decks.removeWords(securityUtils.getCurrentUserId(), deckId, request.vocabularyIds())));
    }

    @PostMapping("/{deckId}/words/transfer")
    public ResponseEntity<ApiResponse<DeckChangeResult>> transfer(@PathVariable long deckId, @Valid @RequestBody DeckTransferRequest request) {
        return ResponseEntity.ok(ApiResponse.success(decks.transfer(securityUtils.getCurrentUserId(), deckId,
                request.targetDeckId(), request.mode(), request.vocabularyIds())));
    }
}
