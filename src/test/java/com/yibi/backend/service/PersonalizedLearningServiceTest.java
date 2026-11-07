package com.yibi.backend.service;

import com.yibi.backend.dto.learningpath.SkillStatResponse;
import com.yibi.backend.service.PersonalizedLearningService.Attempt;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersonalizedLearningServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 12, 0);

    private static List<Attempt> attempts(String skill, int wrong, int right) {
        List<Attempt> list = new ArrayList<>();
        for (int i = 0; i < wrong; i++) list.add(new Attempt(skill, false, NOW.minusDays(1).plusSeconds(i), null));
        for (int i = 0; i < right; i++) list.add(new Attempt(skill, true, NOW.minusHours(1).plusSeconds(i), null));
        return list;
    }

    private static SkillStatResponse stat(List<Attempt> attempts, String skill) {
        return PersonalizedLearningService.buildSkillStats(attempts, NOW).stream()
                .filter(s -> s.getSkillKey().equals(skill)).findFirst().orElseThrow();
    }

    @Test
    void recentAnswersDriveAccuracySoImprovementChangesThePriority() {
        List<Attempt> before = attempts("grammar", 10, 0);
        assertEquals("HIGH", stat(before, "grammar").getPriority());

        List<Attempt> after = attempts("grammar", 10, PersonalizedLearningService.RECENT_ATTEMPTS_WINDOW);
        SkillStatResponse improved = stat(after, "grammar");
        assertEquals(100, improved.getAccuracyPercent());
        assertEquals("LOW", improved.getPriority());
        assertEquals(40, improved.getAttempts());
    }

    @Test
    void fewerThanThreeAnswersIsNotEnoughToJudge() {
        assertEquals("INSUFFICIENT", stat(attempts("reading", 2, 0), "reading").getPriority());
    }
}
