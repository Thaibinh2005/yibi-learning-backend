package com.yibi.backend.service;

import com.yibi.backend.dto.course.CourseRequest;
import com.yibi.backend.dto.course.CourseResponse;
import com.yibi.backend.entity.Course;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseRepository courseRepository;

    @Transactional(readOnly = true)
    public List<CourseResponse> getAllCourses(Integer hskLevel) {
        List<Course> courses;
        if (hskLevel != null) {
            courses = courseRepository.findByHskLevelOrderByHskLevelAsc(hskLevel);
        } else {
            courses = courseRepository.findAll();
        }

        return courses.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CourseResponse getCourseById(Long id) {
        Course course = courseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", id));
        return mapToResponse(course);
    }

    @Transactional
    public CourseResponse createCourse(CourseRequest request) {
        Course course = Course.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .hskLevel(request.getHskLevel())
                .totalLessons(0)
                .build();

        Course saved = courseRepository.save(course);
        log.info("Created course: id={}, title={}, level={}", saved.getId(), saved.getTitle(), saved.getHskLevel());
        return mapToResponse(saved);
    }

    @Transactional
    public CourseResponse updateCourse(Long id, CourseRequest request) {
        Course course = courseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", id));

        course.setTitle(request.getTitle());
        course.setDescription(request.getDescription());
        course.setHskLevel(request.getHskLevel());

        Course updated = courseRepository.save(course);
        log.info("Updated course: id={}, title={}", updated.getId(), updated.getTitle());
        return mapToResponse(updated);
    }

    @Transactional
    public void deleteCourse(Long id) {
        if (!courseRepository.existsById(id)) {
            throw new ResourceNotFoundException("Course", "id", id);
        }
        courseRepository.deleteById(id);
        log.info("Deleted course id={}", id);
    }

    private CourseResponse mapToResponse(Course course) {
        return CourseResponse.builder()
                .id(course.getId())
                .title(course.getTitle())
                .description(course.getDescription())
                .hskLevel(course.getHskLevel())
                .totalLessons(course.getTotalLessons())
                .createdAt(course.getCreatedAt())
                .updatedAt(course.getUpdatedAt())
                .build();
    }
}
