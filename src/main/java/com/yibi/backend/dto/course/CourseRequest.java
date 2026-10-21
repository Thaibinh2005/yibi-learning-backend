package com.yibi.backend.dto.course;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
public class CourseRequest {

    @NotBlank(message = "Course title is required")
    private String title;

    private String description;

    @NotNull(message = "HSK level is required (1-6)")
    @Min(value = 1, message = "HSK level must be between 1 and 6")
    @Max(value = 6, message = "HSK level must be between 1 and 6")
    private Integer hskLevel;
}
