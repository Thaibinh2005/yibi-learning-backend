package com.yibi.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MeasureWordServiceTest {

    @Test
    void yiToneSandhi() {
        assertEquals("yí", MeasureWordService.yiWithSandhi("gè"));    // 一个
        assertEquals("yí", MeasureWordService.yiWithSandhi("liàng")); // 一辆
        assertEquals("yì", MeasureWordService.yiWithSandhi("běn"));   // 一本
        assertEquals("yì", MeasureWordService.yiWithSandhi("zhī"));   // 一只
        assertEquals("yì", MeasureWordService.yiWithSandhi("tiáo"));  // 一条
        assertEquals("yì", MeasureWordService.yiWithSandhi("lǚ"));    // ü with caron
        assertEquals("yí", MeasureWordService.yiWithSandhi("ge"));    // neutral 个
    }

    @Test
    void shortMeaningAndCleanNoun() {
        assertEquals("quyển", MeasureWordService.shortMeaning("quyển; cuốn; tập"));
        assertNull(MeasureWordService.shortMeaning(" "));
        assertEquals("会", MeasureWordService.cleanNoun("会1"));
    }

    @Test
    void meaningPrefersDictionaryClassifierSenseThenCurated() {
        assertEquals("lượng từ cho xe cộ", MeasureWordService.meaningOf("辆", "lượng từ cho xe cộ"));
        assertEquals("quyển, cuốn (sách, vở)", MeasureWordService.meaningOf("本", null));
        assertNull(MeasureWordService.meaningOf("不存在", null));
    }
}
