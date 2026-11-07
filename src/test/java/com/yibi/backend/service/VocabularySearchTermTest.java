package com.yibi.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VocabularySearchTermTest {

    @Test
    void chineseIsMatchedOnlyAsCharacters() {
        VocabularySearchTerm term = VocabularySearchTerm.of(" 学习 ");
        assertThat(term.text()).isEqualTo("学习");
        assertThat(term.pinyin()).isEmpty();
        assertThat(term.word()).isEmpty();
    }

    @Test
    void everySpellingOfPinyinGivesTheSameKey() {
        for (String typed : new String[] {"xuéxí", "xue xi", "xuexi", "XUE2 XI2", "xué-xí"}) {
            assertThat(VocabularySearchTerm.of(typed).pinyin()).as(typed).isEqualTo("xuexi");
        }
        assertThat(VocabularySearchTerm.of("nv").pinyin()).isEqualTo("nu");
        assertThat(VocabularySearchTerm.of("nǚ").pinyin()).isEqualTo("nu");
    }

    @Test
    void vietnameseKeepsAccentsOnlyWhenTyped() {
        VocabularySearchTerm plain = VocabularySearchTerm.of("Hoc  tap");
        assertThat(plain.word()).isEqualTo("hoc tap");
        assertThat(plain.accented()).isFalse();

        VocabularySearchTerm accented = VocabularySearchTerm.of("Học tập");
        assertThat(accented.word()).isEqualTo("học tập");
        assertThat(accented.accented()).isTrue();
    }

    @Test
    void lettersOnlyVietnameseHasAreNeverPinyin() {
        for (String typed : new String[] {"học", "ăn", "đi", "người", "bây giờ", "bảo", "mã"}) {
            assertThat(VocabularySearchTerm.of(typed).pinyin()).as(typed).isEmpty();
        }
        assertThat(VocabularySearchTerm.of("bàn").pinyin()).isEqualTo("ban");
    }

    @Test
    void wildcardsCannotReachTheQuery() {
        VocabularySearchTerm term = VocabularySearchTerm.of("100%_a");
        assertThat(term.word()).isEqualTo("100 a");
        assertThat(term.pinyin()).isEqualTo("a");
    }
}
