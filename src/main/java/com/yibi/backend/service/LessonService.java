package com.yibi.backend.service;

import com.yibi.backend.dto.course.LessonRequest;
import com.yibi.backend.dto.course.LessonResponse;
import com.yibi.backend.entity.Course;
import com.yibi.backend.entity.Lesson;
import com.yibi.backend.entity.LessonContentType;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.repository.CourseRepository;
import com.yibi.backend.repository.LessonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LessonService {

    private final LessonRepository lessonRepository;
    private final CourseRepository courseRepository;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<LessonResponse> getLessonsByCourseId(Long courseId) {
        if (!courseRepository.existsById(courseId)) {
            throw new ResourceNotFoundException("Course", "id", courseId);
        }
        return lessonRepository.findByCourseIdOrderByLessonOrderAsc(courseId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public LessonResponse getLessonById(Long id) {
        Lesson lesson = lessonRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", id));
        return mapToResponse(lesson);
    }

    @Transactional
    public LessonResponse createLesson(LessonRequest request) {
        Course course = courseRepository.findById(request.getCourseId())
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", request.getCourseId()));
        validateContentReference(request.getContentType(), request.getContentRefId());

        Lesson lesson = Lesson.builder()
                .course(course)
                .title(request.getTitle())
                .lessonOrder(request.getLessonOrder() != null ? request.getLessonOrder() : 0)
                .contentType(request.getContentType())
                .contentRefId(request.getContentRefId())
                .build();

        Lesson saved = lessonRepository.save(lesson);
        course.setTotalLessons(course.getTotalLessons() + 1);
        courseRepository.save(course);

        log.info("Created lesson: id={}, title={}, order={}", saved.getId(), saved.getTitle(), saved.getLessonOrder());
        return mapToResponse(saved);
    }

    @Transactional
    public LessonResponse updateLesson(Long id, LessonRequest request) {
        Lesson lesson = lessonRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", id));
        validateContentReference(request.getContentType(), request.getContentRefId());

        if (request.getCourseId() != null && !lesson.getCourse().getId().equals(request.getCourseId())) {
            Course newCourse = courseRepository.findById(request.getCourseId())
                    .orElseThrow(() -> new ResourceNotFoundException("Course", "id", request.getCourseId()));
            lesson.setCourse(newCourse);
        }

        lesson.setTitle(request.getTitle());
        if (request.getLessonOrder() != null) {
            lesson.setLessonOrder(request.getLessonOrder());
        }
        lesson.setContentType(request.getContentType());
        lesson.setContentRefId(request.getContentRefId());

        Lesson updated = lessonRepository.save(lesson);
        log.info("Updated lesson: id={}, title={}", updated.getId(), updated.getTitle());
        return mapToResponse(updated);
    }

    @Transactional
    public void deleteLesson(Long id) {
        Lesson lesson = lessonRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", id));

        Course course = lesson.getCourse();
        if (course != null && course.getTotalLessons() > 0) {
            course.setTotalLessons(course.getTotalLessons() - 1);
            courseRepository.save(course);
        }

        lessonRepository.deleteById(id);
        log.info("Deleted lesson id={}", id);
    }

    private LessonResponse mapToResponse(Lesson lesson) {
        return LessonResponse.builder()
                .id(lesson.getId())
                .courseId(lesson.getCourse() != null ? lesson.getCourse().getId() : null)
                .courseTitle(lesson.getCourse() != null ? lesson.getCourse().getTitle() : null)
                .title(lesson.getTitle())
                .lessonOrder(lesson.getLessonOrder())
                .contentType(lesson.getContentType())
                .contentRefId(lesson.getContentRefId())
                .lessonPayload(lesson.getLessonPayload())
                .createdAt(lesson.getCreatedAt())
                .updatedAt(lesson.getUpdatedAt())
                .build();
    }

    private void validateContentReference(LessonContentType type, Long rawRecordId) {
        if (rawRecordId == null) return;
        String table = switch (type) {
            case VOCABULARY -> "hsk_vocabulary_records";
            case GRAMMAR -> "hsk_grammar_records";
            case HANZI -> "hsk_hanzi_records";
            case READING, LISTENING -> throw new BadRequestException(
                    "Nội dung đọc/nghe cần nằm trong lesson_payload; contentRefId chỉ nhận từ vựng/ngữ pháp HSK V6.");
        };
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE raw_record_id = ?", Integer.class, rawRecordId);
        if (found == null || found == 0) {
            throw new BadRequestException("contentRefId không trỏ đến bản ghi V6 phù hợp với loại nội dung.");
        }
    }
}
