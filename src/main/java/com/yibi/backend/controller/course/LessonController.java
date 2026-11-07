package com.yibi.backend.controller.course;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.course.LessonRequest;
import com.yibi.backend.dto.course.LessonResponse;
import com.yibi.backend.service.LessonService;
import com.yibi.backend.service.AiLessonContentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/lessons")
@RequiredArgsConstructor
public class LessonController {

    private final LessonService lessonService;
    private final AiLessonContentService aiLessonContentService;
    private final com.yibi.backend.service.LessonAccessService lessonAccess;

    @GetMapping("/course/{courseId}")
    public ResponseEntity<ApiResponse<List<LessonResponse>>> getLessonsByCourse(@PathVariable Long courseId) {
        lessonAccess.requireCourse(courseId);
        List<LessonResponse> lessons = lessonService.getLessonsByCourseId(courseId);
        return ResponseEntity.ok(ApiResponse.success(lessons));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<LessonResponse>> getLessonById(@PathVariable Long id) {
        lessonAccess.requireLesson(id);
        LessonResponse lesson = lessonService.getLessonById(id);
        return ResponseEntity.ok(ApiResponse.success(lesson));
    }

    @PostMapping("/{id}/ai-content")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> generateAiLessonContent(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                "AI lesson content is ready",
                aiLessonContentService.generateForLesson(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<LessonResponse>> createLesson(@Valid @RequestBody LessonRequest request) {
        LessonResponse created = lessonService.createLesson(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Lesson created successfully", created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<LessonResponse>> updateLesson(
            @PathVariable Long id,
            @Valid @RequestBody LessonRequest request) {
        LessonResponse updated = lessonService.updateLesson(id, request);
        return ResponseEntity.ok(ApiResponse.success("Lesson updated successfully", updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteLesson(@PathVariable Long id) {
        lessonService.deleteLesson(id);
        return ResponseEntity.ok(ApiResponse.success("Lesson deleted successfully", null));
    }
}
