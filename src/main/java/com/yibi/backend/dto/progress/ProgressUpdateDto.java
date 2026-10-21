package com.yibi.backend.dto.progress;

import com.yibi.backend.entity.ProgressStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgressUpdateDto {

    @NotNull(message = "Lesson ID is required")
    private Long lessonId;

    @NotNull(message = "Status is required")
    private ProgressStatus status;

    private BigDecimal score;
}
