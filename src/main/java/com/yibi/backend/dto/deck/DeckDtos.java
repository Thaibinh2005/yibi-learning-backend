package com.yibi.backend.dto.deck;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Requests and responses of the personal vocabulary decks (/api/decks). */
public final class DeckDtos {

    private DeckDtos() {}

    /** Create or rename a deck. */
    public record DeckNameRequest(
            @NotBlank(message = "Tên bộ từ vựng không được để trống")
            @Size(max = 80, message = "Tên bộ từ vựng tối đa 80 ký tự")
            String name) {}

    /** Words of the HSK bank to add to or remove from a deck. */
    public record DeckWordsRequest(
            @NotEmpty(message = "Chưa chọn từ nào")
            @Size(max = 500, message = "Mỗi lần tối đa 500 từ")
            List<@NotNull Long> vocabularyIds) {}

    public enum TransferMode { MOVE, COPY }

    /** Move or copy words of a deck into another deck of the same learner. */
    public record DeckTransferRequest(
            @NotNull(message = "Chưa chọn bộ đích") Long targetDeckId,
            @NotNull(message = "Chưa chọn di chuyển hay sao chép") TransferMode mode,
            @NotEmpty(message = "Chưa chọn từ nào")
            @Size(max = 500, message = "Mỗi lần tối đa 500 từ")
            List<@NotNull Long> vocabularyIds) {}

    /**
     * A deck with its progress: learnt words have been reviewed at least once, mastered ones answered right three
     * times in a row (as in the flashcard stats), due ones are waiting for review today.
     */
    public record DeckResponse(
            Long id,
            String name,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            long totalWords,
            long learnedWords,
            long masteredWords,
            long dueWords) {}

    /** Memory state of a word, from the learner's flashcard for it. */
    public enum MemoryStatus { NEW, LEARNING, MASTERED }

    public record DeckWordResponse(
            Long vocabularyId,
            String hanzi,
            String pinyin,
            String meaning,
            String levelCode,
            LocalDateTime addedAt,
            LocalDateTime lastReviewed,
            LocalDate dueDate,
            int repetitions,
            int intervalDays,
            MemoryStatus status,
            boolean due) {}

    /** One page of a deck's words after filtering, with every word id of the deck for the "already added" marks. */
    public record DeckWordPage(
            DeckResponse deck,
            List<DeckWordResponse> items,
            long total,
            int page,
            int size,
            List<Long> memberIds) {}

    /** What an add, move or copy did: words changed, and words already in the deck that were left as they were. */
    public record DeckChangeResult(int changed, int skipped) {}
}
