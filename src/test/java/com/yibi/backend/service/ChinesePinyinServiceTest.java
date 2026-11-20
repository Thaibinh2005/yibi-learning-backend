package com.yibi.backend.service;

import com.yibi.backend.service.ChinesePinyinService.Analysis;
import com.yibi.backend.service.ChinesePinyinService.Token;
import com.yibi.backend.service.VocabularyLookupIndex.DictionaryWord;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChinesePinyinServiceTest {

    private static final Map<String, DictionaryWord> DICT = Map.of(
            "我", new DictionaryWord(1, "1", "wǒ"),
            "想", new DictionaryWord(2, "1", "xiǎng"),
            "去", new DictionaryWord(3, "1", "qù"),
            "银行", new DictionaryWord(4, "3", "yínháng"),
            "一起", new DictionaryWord(5, "1", "yīqǐ"),
            "不", new DictionaryWord(6, "1", "bù"),
            "是", new DictionaryWord(7, "1", "shì"),
            "一样", new DictionaryWord(8, "1", "yīyàng"),
            "不客气", new DictionaryWord(9, "1", "bù kè qi"),
            "一月", new DictionaryWord(10, "1", "yīyuè"));

    @Test
    void longestMatchGivesTheWordReadingAndLinksTheRecord() {
        Analysis a = ChinesePinyinService.analyze("我想去银行。", DICT);
        assertThat(a.words()).extracting(Token::w).containsExactly("我", "想", "去", "银行", "。");
        assertThat(a.words().get(3)).isEqualTo(new Token("银行", "yínháng", 4L, "3"));
        assertThat(a.pinyin()).isEqualTo("wǒ xiǎng qù yínháng.");
        assertThat(a.flags()).isEmpty();
    }

    @Test
    void tokensCoverTheWholeText() {
        String text = "Hello，我们 2026 年见！";
        Analysis a = ChinesePinyinService.analyze(text, DICT);
        assertThat(String.join("", a.words().stream().map(Token::w).toList())).isEqualTo(text);
    }

    @Test
    void yiAndBuChangeTone() {
        assertThat(ChinesePinyinService.analyze("一起", DICT).pinyin()).isEqualTo("yìqǐ");
        assertThat(ChinesePinyinService.analyze("一样", DICT).pinyin()).isEqualTo("yíyàng");
        assertThat(ChinesePinyinService.analyze("不是", DICT).pinyin()).isEqualTo("bú shì");
        assertThat(ChinesePinyinService.analyze("不客气", DICT).pinyin()).isEqualTo("búkèqi");
        assertThat(ChinesePinyinService.analyze("不想", DICT).pinyin()).isEqualTo("bù xiǎng");
    }

    @Test
    void yiKeepsItsToneInDates() {
        assertThat(ChinesePinyinService.analyze("一月", DICT).pinyin()).isEqualTo("yīyuè");
    }

    @Test
    void unknownCharactersFallBackAndPolyphonesAreFlagged() {
        Analysis a = ChinesePinyinService.analyze("行", Map.of());
        assertThat(a.words().get(0).py()).isNotBlank();
        assertThat(a.words().get(0).id()).isNull();
        assertThat(a.flags()).contains(ChinesePinyinService.POLYPHONE);
    }

    @Test
    void traditionalTextIsLookedUpAsSimplified() {
        Analysis a = ChinesePinyinService.analyze("銀行", DICT);
        assertThat(a.words()).containsExactly(new Token("銀行", "yínháng", 4L, "3"));
    }

    @Test
    void firstToneReadsTheMark() {
        assertThat(ChinesePinyinService.firstTone("shì")).isEqualTo(4);
        assertThat(ChinesePinyinService.firstTone("qi")).isZero();
        assertThat(ChinesePinyinService.firstTone("lǜ")).isEqualTo(4);
    }
}
