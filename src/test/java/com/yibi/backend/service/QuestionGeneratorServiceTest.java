package com.yibi.backend.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class QuestionGeneratorServiceTest {

    public static void main(String[] args) {
        QuestionGeneratorServiceTest t = new QuestionGeneratorServiceTest();
        t.testNormalizePinyin();
        t.testSharesMeaningFragment();
        System.out.println("ALL TESTS PASSED SUCCESSFULLY!");
    }

    @Test
    void testNormalizePinyin() {
        // Space, hyphens, and apostrophe stripped, NFC normalized, tones preserved
        assertEquals("hǎoshì", QuestionGeneratorService.normalizePinyin("hǎoshì"));
        assertEquals("hǎoshì", QuestionGeneratorService.normalizePinyin("hǎo shì"));
        assertEquals("hǎoshì", QuestionGeneratorService.normalizePinyin("hǎo-shì"));
        assertEquals("yīkuàiēr", QuestionGeneratorService.normalizePinyin("yīkuài'ēr"));
        assertEquals("yīkuàiēr", QuestionGeneratorService.normalizePinyin("yīkuàiēr"));
        assertEquals("zhōngguórén", QuestionGeneratorService.normalizePinyin("zhōng guó rén"));
    }

    @Test
    void testSharesMeaningFragment() {
        assertTrue(QuestionGeneratorService.sharesMeaningFragment(
                "một lúc; chốc lát", "một lúc"));
        assertTrue(QuestionGeneratorService.sharesMeaningFragment(
                "gia đình, nhà", "nhà"));
        assertTrue(QuestionGeneratorService.sharesMeaningFragment(
                "học tập (dạng khẩu ngữ)", "học tập"));

        assertFalse(QuestionGeneratorService.sharesMeaningFragment(
                "người yêu", "bạn bè"));
        assertFalse(QuestionGeneratorService.sharesMeaningFragment(
                "đi bộ", "chạy bộ"));
    }
}
