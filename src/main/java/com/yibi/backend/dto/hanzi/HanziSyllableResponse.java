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
    /** "1" to "6", "7-9", or "1-2" for the list shared by levels 1 and 2. */
    private String levelCode;
}
