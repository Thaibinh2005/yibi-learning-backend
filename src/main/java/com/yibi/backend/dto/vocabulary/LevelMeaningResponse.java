package com.yibi.backend.dto.vocabulary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The meaning of a word for the HSK level it is taught at, as the official syllabus introduces it there (分 at
 * HSK 1 "phút", at HSK 3 the verb "chia"). Present only when the word, level and pinyin are in the official list;
 * otherwise the client shows the dictionary meaning (meaningVi).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LevelMeaningResponse {

    /** e.g. "HSK3.0-2025-11" */
    private String syllabusVersion;
    private String level;
    private String pinyin;
    /** Parts of speech introduced at this level, as the syllabus writes them, e.g. "动、名". */
    private String partOfSpeech;
    /** The same in Vietnamese, e.g. "Động từ, Danh từ". */
    private String partOfSpeechVi;
    private String main;
    private List<String> secondary;
    private List<Collocation> collocations;
    /** Usage note, from HSK 4 on. */
    private String note;
    /** What this level adds when the word was taught at a lower level already. */
    private String newAtLevel;
    /**
     * The meanings of the same word (same reading) taught at lower levels, lowest first, for the word page; a
     * flashcard shows only the meaning of its own level.
     */
    private List<LevelMeaningResponse> earlierLevels;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Collocation {
        private String zh;
        private String vi;
    }
}
