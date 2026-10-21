package com.yibi.backend.dto.exam;

import com.yibi.backend.entity.QuestionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionDto {

    private Long id;
    private QuestionType questionType;
    private String content;
    private String audioUrl;
    private String options;
    private Integer hskLevel;
    // Note: correctAnswer is omitted so students cannot cheat!
}
