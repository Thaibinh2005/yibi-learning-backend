package com.yibi.backend.dto.vocabulary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VocabularyExampleResponse {

    private Long id;
    private String sentenceZh;
    private String sentencePinyin;
    private String translationVi;
    private String source;
}

