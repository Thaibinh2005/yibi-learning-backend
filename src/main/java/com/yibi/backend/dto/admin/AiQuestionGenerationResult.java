package com.yibi.backend.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiQuestionGenerationResult {

    private int requested;
    private String model;
    /** Ids of the DRAFT questions that were saved. */
    private List<Long> createdIds;
    /** Why each rejected question was not saved. */
    private List<String> rejected;
}
