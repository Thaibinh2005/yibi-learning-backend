package com.yibi.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WordDetailsServiceTest {

    @Test
    void listsEachChineseCharacterOnceInOrder() {
        assertThat(WordDetailsService.distinctCharacters("谢谢")).containsExactly("谢");
        assertThat(WordDetailsService.distinctCharacters("学生")).containsExactly("学", "生");
        assertThat(WordDetailsService.distinctCharacters("卡拉OK")).containsExactly("卡", "拉");
    }

    @Test
    void keepsTheFirstDictionaryMeaningShort() {
        assertThat(WordDetailsService.shortMeaning("học; học tập")).isEqualTo("học");
        assertThat(WordDetailsService.shortMeaning(null)).isNull();
        assertThat(WordDetailsService.shortMeaning("x".repeat(100))).hasSize(60).endsWith("…");
    }
}
