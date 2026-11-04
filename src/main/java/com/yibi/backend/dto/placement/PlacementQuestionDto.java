package com.yibi.backend.dto.placement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** A question as the learner sees it: no answer, level or difficulty. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlacementQuestionDto {

    private Long id;
    private String skill;
    private String type;
    private String prompt;
    private String promptPinyin;
    /** An audio URL, or {@code tts:<text>} when the client should read the text aloud. */
    private String audioUrl;
    private List<Choice> choices;
    private int order;

    @Data
    @AllArgsConstructor
    public static class Choice {
        private String key;
        private String text;
    }
}
