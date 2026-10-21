package com.yibi.backend.dto.progress;

import com.yibi.backend.entity.ProgressStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProgressResponse {

    private Long lessonId;
    private String lessonTitle;
    private Long courseId;
    private String courseTitle;
    private ProgressStatus status;
    private BigDecimal score;
    private LocalDateTime completedAt;
}
