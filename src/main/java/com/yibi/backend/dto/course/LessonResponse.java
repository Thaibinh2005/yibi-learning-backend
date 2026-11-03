package com.yibi.backend.dto.course;

import com.yibi.backend.entity.LessonContentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LessonResponse {

    private Long id;
    private Long courseId;
    private String courseTitle;
    private String title;
    private Integer lessonOrder;
    private LessonContentType contentType;
    private Long contentRefId;
    private Map<String, Object> lessonPayload;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
