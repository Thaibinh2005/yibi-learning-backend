package com.yibi.backend.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PartOfSpeechViTest {

    @Test
    void translatesSingleTag() {
        assertEquals("Danh từ", PartOfSpeechVi.translate("名"));
        assertEquals("Số lượng từ", PartOfSpeechVi.translate("数量"));
    }

    @Test
    void translatesListWithOptionalGroup() {
        assertEquals("Lượng từ, (Danh từ)", PartOfSpeechVi.translate("量、（名）"));
        assertEquals("Động từ, (Danh từ, Lượng từ)", PartOfSpeechVi.translate("动、（名、量）"));
    }

    @Test
    void repairsTruncatedSourceLabel() {
        assertEquals("Tính từ, Đại từ, (Động từ, Số từ)", PartOfSpeechVi.translate("形、代、（动、数、"));
    }

    @Test
    void handlesEmptyAndUnknown() {
        assertNull(PartOfSpeechVi.translate(null));
        assertNull(PartOfSpeechVi.translate("  "));
        assertEquals("Danh từ, Động từ", PartOfSpeechVi.translate("名动"));
        assertEquals("XYZ", PartOfSpeechVi.translate("XYZ"));
    }
}
