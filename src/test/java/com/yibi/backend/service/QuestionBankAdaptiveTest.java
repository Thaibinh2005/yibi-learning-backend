package com.yibi.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.dto.practice.MasterySummaryResponse;
import com.yibi.backend.dto.practice.SkillMasteryStat;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class QuestionBankAdaptiveTest {

    public static void main(String[] args) {
        QuestionBankAdaptiveTest test = new QuestionBankAdaptiveTest();
        test.testAdaptiveFlowAndMastery();
        System.out.println(">>> ALL ADAPTIVE AND MASTERY TESTS PASSED! <<<");
    }

    // Connects to the real local database and writes practice sessions for the default student account.
    // Run it only by hand against a throwaway database, until it is moved to an isolated test database.
    @org.junit.jupiter.api.Disabled("Writes to the real local database")
    @Test
    void testAdaptiveFlowAndMastery() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://localhost:5432/yibi_db");
        dataSource.setUsername("yibi_user");
        dataSource.setPassword("yibi_secret");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper();
        QuestionBankService service = new QuestionBankService(jdbcTemplate, objectMapper);

        // Student UUID in database
        UUID studentId = UUID.fromString("bcb92314-2ef0-4a0d-88e0-a688833e0539");

        // 1. Test starting adaptive session for HSK 1, limit 5
        Map<String, Object> session = service.startAdaptiveSession(studentId, "1", 5);
        assertNotNull(session);
        Long sessionId = (Long) session.get("sessionId");
        assertNotNull(sessionId);
        assertEquals("1", session.get("levelCode"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questions = (List<Map<String, Object>>) session.get("questions");
        assertNotNull(questions);
        assertEquals(5, questions.size(), "Should have fetched 5 adaptive questions");

        // Verify each question structure
        Map<String, Object> firstQ = questions.get(0);
        String questionCode = (String) firstQ.get("questionCode");
        assertNotNull(questionCode);
        assertNotNull(firstQ.get("prompt"));
        assertNotNull(firstQ.get("selectionReason"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> options = (List<Map<String, String>>) firstQ.get("options");
        assertTrue(options.size() >= 2);

        System.out.println("Adaptive Session started: " + sessionId + ", questions: " + questions.size());

        // 2. Submit answers for the 5 questions
        for (int i = 0; i < questions.size(); i++) {
            Map<String, Object> q = questions.get(i);
            String qCode = (String) q.get("questionCode");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> qOptions = (List<Map<String, String>>) q.get("options");

            // Look up correct answer to alternate or simulate realistic answers
            String correctAnswer = jdbcTemplate.queryForObject(
                    "SELECT answer_text FROM question_bank_items WHERE question_code=?",
                    String.class, qCode);

            // Let's answer correctly
            Map<String, Object> ansResult = service.submitAnswer(sessionId, studentId, qCode, correctAnswer, 1200);
            assertNotNull(ansResult);
            assertTrue((Boolean) ansResult.get("correct"));
            assertNotNull(ansResult.get("masteryScore"));
            assertNotNull(ansResult.get("nextReviewAt"));
            assertTrue(((Number) ansResult.get("streak")).intValue() >= 1);

            System.out.println("Answered Q" + (i + 1) + " [" + qCode + "]: correct=" + ansResult.get("correct")
                    + ", mastery=" + ansResult.get("masteryScore")
                    + ", streak=" + ansResult.get("streak")
                    + ", nextReview=" + ansResult.get("nextReviewAt"));
        }

        // 3. Verify session completed
        String sessionState = jdbcTemplate.queryForObject(
                "SELECT session_state FROM practice_sessions WHERE id=?",
                String.class, sessionId);
        assertEquals("COMPLETED", sessionState);

        // 4. Test getMasterySummary
        MasterySummaryResponse summary = service.getMasterySummary(studentId);
        assertNotNull(summary);
        assertTrue(summary.getTotalAttempts() >= 5);
        assertTrue(summary.getTotalCorrect() >= 5);
        assertTrue(summary.getOverallAccuracy() > 0);
        assertTrue(summary.getAverageMastery() > 0);
        assertNotNull(summary.getSkills());
        assertEquals(4, summary.getSkills().size());

        for (SkillMasteryStat sk : summary.getSkills()) {
            System.out.println("Skill: " + sk.getSkillKey() + " -> attempts=" + sk.getAttemptsCount()
                    + ", correct=" + sk.getCorrectCount()
                    + ", mastery=" + sk.getMasteryScore()
                    + ", accuracy=" + sk.getAccuracyPercent() + "%");
        }

        System.out.println("Mastery Summary: totalAttempts=" + summary.getTotalAttempts()
                + ", totalCorrect=" + summary.getTotalCorrect()
                + ", overallAccuracy=" + summary.getOverallAccuracy() + "%"
                + ", avgMastery=" + summary.getAverageMastery());
    }
}

