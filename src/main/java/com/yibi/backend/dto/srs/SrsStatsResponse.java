package com.yibi.backend.dto.srs;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SrsStatsResponse {

    private long totalCards;
    private long dueToday;
    private long learningCards;   // repetition < 3
    private long masteredCards;   // repetition >= 3
}
