package com.yibi.backend.dto.practice;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AdaptiveSessionRequest {
    @Pattern(regexp = "1|2|3|4|5|6|7-9")
    private String levelCode = "1";

    @Min(5)
    @Max(30)
    private int limit = 10;
}
