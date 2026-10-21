package com.yibi.backend.dto.course;

import com.yibi.backend.entity.LessonContentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LessonRequest {

    @NotNull(message = "Course ID is required")
    private Long courseId;

    @NotBlank(message = "Lesson title is required")
    private String title;

    @Builder.Default
    private Integer lessonOrder = 0;

    @NotNull(message = "Content type is required")
    private LessonContentType contentType;

    private Long contentRefId;
}
