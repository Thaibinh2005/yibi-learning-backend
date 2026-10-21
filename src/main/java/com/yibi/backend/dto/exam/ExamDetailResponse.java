package com.yibi.backend.dto.exam;

import com.yibi.backend.entity.ExamType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamDetailResponse {

    private Long id;
    private String title;
    private Integer hskLevel;
    private ExamType examType;
    private Integer durationMinutes;
    private List<QuestionDto> questions;
}
