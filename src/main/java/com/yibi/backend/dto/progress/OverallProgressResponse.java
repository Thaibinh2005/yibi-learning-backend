package com.yibi.backend.dto.progress;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverallProgressResponse {

    private long completedLessons;
    private int currentHskLevel;
    private long srsCardsDueToday;
    private long srsTotalCards;
    private long examsAttempted;
}
