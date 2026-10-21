package com.yibi.backend.dto.exam;

import com.yibi.backend.entity.ExamType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamResponse {

    private Long id;
    private String title;
    private Integer hskLevel;
    private ExamType examType;
    private Integer durationMinutes;
    private int questionCount;
    private LocalDateTime createdAt;
}
