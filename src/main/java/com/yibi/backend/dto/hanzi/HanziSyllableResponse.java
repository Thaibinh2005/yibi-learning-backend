package com.yibi.backend.dto.hanzi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One HSK writing character and the reading it was found under. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HanziSyllableResponse {

    private String hanzi;
    private String pinyin;
    /** "1" to "6", or "7-9". */
    private String levelCode;
}
