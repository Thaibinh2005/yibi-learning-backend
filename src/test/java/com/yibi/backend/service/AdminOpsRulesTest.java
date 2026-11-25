package com.yibi.backend.service;

import com.yibi.backend.exception.AppException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminOpsRulesTest {

    @Test
    void groupsGeminiAnswersIntoFourOutcomes() {
        assertThat(GeminiUsageTracker.outcome(200)).isEqualTo("ok");
        assertThat(GeminiUsageTracker.outcome(429)).isEqualTo("429");
        assertThat(GeminiUsageTracker.outcome(503)).isEqualTo("503");
        assertThat(GeminiUsageTracker.outcome(400)).isEqualTo("other");
        assertThat(GeminiUsageTracker.outcome(500)).isEqualTo("other");
        assertThat(GeminiUsageTracker.key(LocalDate.of(2026, 11, 21))).isEqualTo("gemini:usage:2026-11-21");
    }

    @Test
    void acceptsOnlyTheTwoRoles() {
        assertThat(AdminUserService.validRole("admin")).isEqualTo("ROLE_ADMIN");
        assertThat(AdminUserService.validRole("ROLE_USER")).isEqualTo("ROLE_USER");
        assertThatThrownBy(() -> AdminUserService.validRole("ROLE_ROOT")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> AdminUserService.validRole(null)).isInstanceOf(AppException.class);
    }
}
