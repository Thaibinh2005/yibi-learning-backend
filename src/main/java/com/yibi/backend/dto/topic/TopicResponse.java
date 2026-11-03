package com.yibi.backend.dto.topic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopicResponse {
    private Long id;
    private String slug;
    private String titleVi;
    private String titleZh;
    private String icon;
    private String description;
    private String hskLevel;
    private int wordCount;
    private List<SampleWordDto> sampleWords;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SampleWordDto {
        private Long id;
        private String hanzi;
        private String pinyin;
        private String meaningVi;
        private String levelCode;
    }
}

