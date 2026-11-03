package com.yibi.backend.dto.srs;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SrsAddLessonResponse {
    private int added;
    private int alreadyInDeck;
    private int unavailable;
}
