package com.yibi.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.dto.exam.*;
import com.yibi.backend.entity.*;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExamService {

    private final ExamRepository examRepository;
    private final QuestionRepository questionRepository;
    private final ExamAttemptRepository examAttemptRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<ExamResponse> getExams(Integer hskLevel, ExamType examType) {
        List<Exam> exams;
        if (hskLevel != null && examType != null) {
            exams = examRepository.findByHskLevelAndExamType(hskLevel, examType);
        } else if (hskLevel != null) {
            exams = examRepository.findByHskLevel(hskLevel);
        } else if (examType != null) {
            exams = examRepository.findByExamType(examType);
        } else {
            exams = examRepository.findAll();
        }

        return exams.stream().map(this::mapToExamResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ExamDetailResponse getExamForTaking(Long examId) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        List<Question> questions = questionRepository.findByExamId(examId);
        List<QuestionDto> questionDtos = questions.stream()
                .map(q -> QuestionDto.builder()
                        .id(q.getId())
                        .questionType(q.getQuestionType())
                        .content(q.getContent())
                        .audioUrl(q.getAudioUrl())
                        .options(q.getOptions())
                        .hskLevel(q.getHskLevel())
                        .build())
                .collect(Collectors.toList());

        return ExamDetailResponse.builder()
                .id(exam.getId())
                .title(exam.getTitle())
                .hskLevel(exam.getHskLevel())
                .examType(exam.getExamType())
                .durationMinutes(exam.getDurationMinutes())
                .questions(questionDtos)
                .build();
    }

    @Transactional
    public ExamAttemptResponse submitExam(UUID userId, ExamSubmitRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        Exam exam = examRepository.findById(request.getExamId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", request.getExamId()));

        List<Question> questions = questionRepository.findByExamId(exam.getId());
        if (questions.isEmpty()) {
            throw new BadRequestException("This exam has no questions configured");
        }

        int totalQuestions = questions.size();
        int correctCount = 0;
        Map<Long, String> submittedAnswers = request.getAnswers() != null ? request.getAnswers() : Collections.emptyMap();

        for (Question q : questions) {
            String submitted = submittedAnswers.get(q.getId());
            if (submitted != null && submitted.trim().equalsIgnoreCase(q.getCorrectAnswer().trim())) {
                correctCount++;
            }
        }

        BigDecimal score = BigDecimal.valueOf((double) correctCount / totalQuestions * 100)
                .setScale(2, RoundingMode.HALF_UP);
        boolean passed = score.compareTo(BigDecimal.valueOf(60.0)) >= 0;

        String answersJson;
        try {
            answersJson = objectMapper.writeValueAsString(submittedAnswers);
        } catch (JsonProcessingException e) {
            answersJson = "{}";
        }

        ExamAttempt attempt = ExamAttempt.builder()
                .user(user)
                .exam(exam)
                .score(score)
                .passed(passed)
                .answers(answersJson)
                .timeTakenSeconds(request.getTimeTakenSeconds())
                .startedAt(LocalDateTime.now().minusSeconds(request.getTimeTakenSeconds() != null ? request.getTimeTakenSeconds() : 0))
                .finishedAt(LocalDateTime.now())
                .build();

        ExamAttempt saved = examAttemptRepository.save(attempt);
        log.info("Exam submitted: attemptId={}, userId={}, examId={}, score={}, passed={}",
                saved.getId(), userId, exam.getId(), score, passed);

        return ExamAttemptResponse.builder()
                .attemptId(saved.getId())
                .examId(exam.getId())
                .examTitle(exam.getTitle())
                .score(score)
                .passed(passed)
                .correctCount(correctCount)
                .totalQuestions(totalQuestions)
                .timeTakenSeconds(request.getTimeTakenSeconds())
                .startedAt(saved.getStartedAt())
                .finishedAt(saved.getFinishedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public List<ExamAttemptResponse> getUserAttempts(UUID userId) {
        return examAttemptRepository.findByUserIdOrderByStartedAtDesc(userId)
                .stream()
                .map(a -> ExamAttemptResponse.builder()
                        .attemptId(a.getId())
                        .examId(a.getExam().getId())
                        .examTitle(a.getExam().getTitle())
                        .score(a.getScore())
                        .passed(a.getPassed())
                        .timeTakenSeconds(a.getTimeTakenSeconds())
                        .startedAt(a.getStartedAt())
                        .finishedAt(a.getFinishedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private ExamResponse mapToExamResponse(Exam e) {
        long qCount = questionRepository.countByExamId(e.getId());
        return ExamResponse.builder()
                .id(e.getId())
                .title(e.getTitle())
                .hskLevel(e.getHskLevel())
                .examType(e.getExamType())
                .durationMinutes(e.getDurationMinutes())
                .questionCount((int) qCount)
                .createdAt(e.getCreatedAt())
                .build();
    }
}
