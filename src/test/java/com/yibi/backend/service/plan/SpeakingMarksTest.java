package com.yibi.backend.service.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpeakingMarksTest {

    private static List<String> readings(String sentence, String pinyin) {
        return PinyinByChar.split(sentence, pinyin).stream().map(entry -> entry.get("pinyin")).toList();
    }

    private static String marks(String sentence, String heard) {
        StringBuilder out = new StringBuilder();
        for (Map<String, String> mark : SpeechAlignment.align(sentence, heard).marks()) {
            out.append(switch (mark.get("mark")) {
                case SpeechAlignment.RIGHT -> 'o';
                case SpeechAlignment.WRONG -> 'x';
                default -> '_';
            });
        }
        return out.toString();
    }

    @Test
    void eachCharacterGetsItsOwnSyllable() {
        assertThat(readings("你好！我叫小王。", "Nǐ hǎo! Wǒ jiào Xiǎo Wáng.")).containsExactly("Nǐ", "hǎo", "", "Wǒ", "jiào", "Xiǎo", "Wáng", "");
        assertThat(readings("学习汉语", "xuéxí Hànyǔ")).containsExactly("xué", "xí", "Hàn", "yǔ");
        assertThat(readings("中国人", "Zhōngguórén")).containsExactly("Zhōng", "guó", "rén");
    }

    @Test
    void apostrophesUmlautsAndErhuaSplitRight() {
        assertThat(readings("女儿", "nǚ'ér")).containsExactly("nǚ", "ér");
        assertThat(readings("天安门", "Tiān'ānmén")).containsExactly("Tiān", "ān", "mén");
        assertThat(readings("一会儿", "yíhuìr")).containsExactly("yí", "huì", "r");
        assertThat(readings("绿色", "lǜsè")).containsExactly("lǜ", "sè");
    }

    @Test
    void aMismatchFallsBackToTheDictionary() {
        assertThat(readings("你好", "nǐ")).containsExactly("nǐ", "hǎo");
        assertThat(readings("你好", null)).containsExactly("nǐ", "hǎo");
    }

    @Test
    void rightCharactersAreMarkedOneByOne() {
        assertThat(marks("你好，我叫小王。", "你好我叫小王")).isEqualTo("oo_oooo_");
        assertThat(marks("你好，我叫小王。", "你好我叫小黄")).isEqualTo("oo_ooox_");
        assertThat(marks("你好，我叫小王。", "你好")).isEqualTo("oo_xxxx_");
        assertThat(marks("我叫小王", "")).isEqualTo("xxxx");
    }

    @Test
    void anExtraWordDoesNotShiftTheMarks() {
        assertThat(marks("我叫小王", "我呢叫小王")).isEqualTo("oooo");
    }

    @Test
    void traditionalCharactersAndDigitsCount() {
        assertThat(marks("我们学习汉语", "我們學習漢語")).isEqualTo("oooooo");
        assertThat(marks("我有三本书", "我有3本書")).isEqualTo("ooooo");
        assertThat(SpeechAlignment.align("我们学习汉语", "我們學習漢語").accuracy()).isEqualTo(1.0);
    }

    @Test
    void accuracyFollowsTheMarks() {
        assertThat(SpeechAlignment.align("我叫小王", "我叫小黄").accuracy()).isEqualTo(0.75);
        assertThat(SpeechAlignment.align("。", "你好").accuracy()).isZero();
    }
}
