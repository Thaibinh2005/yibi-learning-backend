package com.yibi.backend.service;

import com.yibi.backend.dto.deck.DeckDtos.DeckWordResponse;
import com.yibi.backend.dto.deck.DeckDtos.MemoryStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VocabDeckServiceTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 1, 9, 0);

    private static DeckWordResponse word(String hanzi, int addedDay, Integer reviewedDay, MemoryStatus status, int repetitions,
                                         int interval, boolean due) {
        return new DeckWordResponse((long) hanzi.hashCode(), hanzi, "", "", "1", T.plusDays(addedDay),
                reviewedDay == null ? null : T.plusDays(reviewedDay), LocalDate.of(2026, 11, 20), repetitions, interval, status, due);
    }

    private final DeckWordResponse fresh = word("新", 3, null, MemoryStatus.NEW, 0, 1, true);
    private final DeckWordResponse shaky = word("学", 1, 5, MemoryStatus.LEARNING, 1, 1, true);
    private final DeckWordResponse steady = word("习", 2, 4, MemoryStatus.LEARNING, 2, 3, false);
    private final DeckWordResponse known = word("好", 0, 6, MemoryStatus.MASTERED, 4, 7, false);

    private List<String> sorted(String sort, String direction) {
        List<DeckWordResponse> words = new ArrayList<>(List.of(known, steady, fresh, shaky));
        words.sort(VocabDeckService.order(sort, direction));
        return words.stream().map(DeckWordResponse::hanzi).toList();
    }

    @Test
    void sortsByDateAddedNewestFirstByDefault() {
        assertThat(sorted(null, null)).containsExactly("新", "习", "学", "好");
        assertThat(sorted("added", "asc")).containsExactly("好", "学", "习", "新");
    }

    @Test
    void sortsByLastReviewKeepingNeverReviewedWordsLast() {
        assertThat(sorted("reviewed", null)).containsExactly("好", "学", "习", "新");
        assertThat(sorted("reviewed", "asc")).containsExactly("习", "学", "好", "新");
    }

    @Test
    void sortsByMemoryWeakestFirst() {
        assertThat(sorted("memory", null)).containsExactly("新", "学", "习", "好");
        assertThat(sorted("memory", "desc")).containsExactly("好", "习", "学", "新");
    }

    @Test
    void filtersByMemoryState() {
        assertThat(VocabDeckService.matchesStatus(fresh, "NEW")).isTrue();
        assertThat(VocabDeckService.matchesStatus(fresh, "LEARNED")).isFalse();
        assertThat(VocabDeckService.matchesStatus(steady, "LEARNED")).isTrue();
        assertThat(VocabDeckService.matchesStatus(known, "MASTERED")).isTrue();
        assertThat(VocabDeckService.matchesStatus(shaky, "DUE")).isTrue();
        assertThat(VocabDeckService.matchesStatus(known, "DUE")).isFalse();
    }
}
