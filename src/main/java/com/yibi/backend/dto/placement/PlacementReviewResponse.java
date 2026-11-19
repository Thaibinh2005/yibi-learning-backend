package com.yibi.backend.dto.placement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/** Every question of a finished placement attempt, in the order it was asked, with the learner's answer. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlacementReviewResponse {
    private Long attemptId;
    private int level;
    private Instant finishedAt;
    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private int order;
        /** Null when the question has since been deleted from the bank. */
        private Long questionId;
        private String skill;
        private String skillLabel;
        private String type;
        /** HSK level of the question: "1" to "6" or "7-9". */
        private String levelCode;
        private String prompt;
        private String promptPinyin;
        /** An audio URL, or {@code tts:<text>} when the client should read the text aloud. */
        private String audioUrl;
        private List<Choice> choices;
        /** The key the learner picked; null for a speaking question. */
        private String selectedChoiceKey;
        /** The expected answer when there are no choices, such as the sentence to read aloud. */
        private String answerText;
        private String explanation;
        private boolean correct;
        private int responseTimeSeconds;
    }

    @Data
    @AllArgsConstructor
    public static class Choice {
        private String key;
        private String text;
        private boolean correct;
    }
}
