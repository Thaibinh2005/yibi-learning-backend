package com.yibi.backend.service;

import com.yibi.backend.service.VocabularyLookupIndex.Entry;
import com.yibi.backend.service.VocabularyLookupIndex.Row;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VocabularyLookupIndexTest {

    private static Row row(long id, String word, String level, String pinyin, String meaning) {
        return new Row(id, word, null, level, pinyin, meaning);
    }

    private static List<String> found(List<Entry> entries, String level, String search) {
        return VocabularyLookupIndex.search(entries, level, VocabularySearchTerm.of(search)).stream().map(Entry::simplified).toList();
    }

    @Test
    void importsOfOneWordAtOneLevelBecomeOneEntry() {
        List<Entry> entries = VocabularyLookupIndex.build(List.of(
                row(1, "学习", "1", "xuéxí", null),
                row(2, "学习", "1", "xuéxí", "học; tìm hiểu"),
                row(3, "学习", "1", "xuéxí", "học; tìm hiểu"),
                row(4, "行", "1", "xíng", "đi"),
                row(5, "行", "5", "háng", "hàng")));
        assertThat(entries).extracting(Entry::simplified, Entry::level)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("学习", "1"), org.assertj.core.groups.Tuple.tuple("行", "1"), org.assertj.core.groups.Tuple.tuple("行", "5"));
    }

    @Test
    void theMeaningComesFromTheFirstRecordThatHasOne() {
        Entry entry = VocabularyLookupIndex.build(List.of(
                row(1, "三", "1", "sān", null),
                row(2, "三", "1", "sān", "ba; 3; họ San"),
                row(3, "三", "1", "sān", "họ [San1]"))).get(0);
        assertThat(entry.recordId()).isEqualTo(2);
        assertThat(entry.accentedMeaning()).contains(" ; ba ; ");
    }

    @Test
    void theReadingIsTheOneMostRecordsAgreeOn() {
        List<Entry> entries = VocabularyLookupIndex.build(List.of(
                row(1, "成绩", "2", "chéngjī", "kết quả"),
                row(2, "成绩", "2", "chéngjì", "kết quả"),
                row(3, "成绩", "3", "chéngjì", "kết quả"),
                row(4, "成绩", "3", "chéngjì", "kết quả"),
                row(5, "时候", "1", "shíhou", "lúc"),
                row(6, "时候", "1", "shíhou", "lúc"),
                row(7, "时候", "1", "shíhòu", "lúc")));
        assertThat(entries).extracting(Entry::pinyin).containsExactly("chéngjì", "chéngjì", "shíhou");
    }

    @Test
    void aWordIsFoundByAnyOfItsReadings() {
        List<Entry> entries = VocabularyLookupIndex.build(List.of(
                row(1, "血", "4", "xiě", "máu"),
                row(2, "血", "4", "xiě", "máu"),
                row(3, "血", "4", "xuè", "máu")));
        assertThat(entries.get(0).pinyin()).isEqualTo("xiě");
        assertThat(found(entries, null, "xue")).containsExactly("血");
        assertThat(found(entries, null, "xie")).containsExactly("血");
    }

    @Test
    void theClosestMatchComesFirst() {
        List<Entry> entries = VocabularyLookupIndex.build(List.of(
                row(1, "上", "1", "shàng", "(hình thức kết hợp) đi học"),
                row(2, "读书", "1", "dúshū", "đọc sách; học hành"),
                row(3, "学", "1", "xué", "học; học tập"),
                row(4, "学校", "1", "xuéxiào", "trường học")));
        assertThat(found(entries, null, "học")).startsWith("学");
        assertThat(found(entries, null, "hoc")).startsWith("学");
        assertThat(found(entries, null, "xue")).containsExactly("学", "学校");
        assertThat(found(entries, null, "学")).containsExactly("学", "学校");
    }

    @Test
    void wordsMatchFromTheirStartNotInTheMiddle() {
        List<Entry> entries = VocabularyLookupIndex.build(List.of(
                row(1, "吃", "1", "chī", "ăn"),
                row(2, "朋友", "1", "péngyou", "bạn"),
                row(3, "安", "2", "ān", "an toàn")));
        assertThat(found(entries, null, "an")).containsExactly("安", "吃");
    }

    @Test
    void accentsNarrowTheSearchOnlyWhenTyped() {
        List<Entry> entries = VocabularyLookupIndex.build(List.of(
                row(1, "吃", "1", "chī", "ăn"),
                row(2, "安", "2", "ān", "an toàn")));
        assertThat(found(entries, null, "ăn")).containsExactly("吃");
    }

    @Test
    void aLevelFilterAppliesToBothBrowsingAndSearching() {
        List<Entry> entries = VocabularyLookupIndex.build(List.of(
                row(1, "学", "1", "xué", "học"),
                row(2, "学期", "2", "xuéqí", "học kỳ"),
                row(3, "学者", "7-9", "xuézhě", "học giả")));
        assertThat(found(entries, "2", "xue")).containsExactly("学期");
        assertThat(VocabularyLookupIndex.search(entries, "7-9", null)).extracting(Entry::simplified).containsExactly("学者");
    }

    @Test
    void aWordWithoutMeaningIsStillListed() {
        List<Entry> entries = VocabularyLookupIndex.build(List.of(row(1, "嗯", "1", "èng", null)));
        assertThat(found(entries, null, "eng")).containsExactly("嗯");
    }
}
