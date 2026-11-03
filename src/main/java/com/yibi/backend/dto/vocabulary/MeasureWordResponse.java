package com.yibi.backend.dto.vocabulary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A measure word (量词) used with a noun, plus a ready-made "一 + 量词 + 名词" example. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeasureWordResponse {
    /** Simplified form, e.g. 本 */
    private String measureWord;
    /** Traditional form when it differs, e.g. 個 */
    private String measureWordTraditional;
    /** Tone-marked pinyin, e.g. běn */
    private String pinyin;
    /** Short Vietnamese meaning of the measure word, e.g. "quyển" */
    private String meaningVi;
    /** Example phrase, e.g. 一本书 */
    private String examplePhrase;
    /** Example pinyin with 一 tone sandhi applied, e.g. yì běn shū */
    private String examplePinyin;
}
