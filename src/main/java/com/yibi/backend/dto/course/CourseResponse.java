package com.yibi.backend.dto.course;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseResponse {

    private Long id;
    private String title;
    private String description;
    private Integer hskLevel;
    private Integer totalLessons;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
