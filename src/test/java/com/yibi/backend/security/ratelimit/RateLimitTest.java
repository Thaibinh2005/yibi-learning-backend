package com.yibi.backend.security.ratelimit;

import com.yibi.backend.exception.AppException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitTest {

    /** Counters in memory instead of Redis. */
    static class FakeLimiter extends RateLimiter {
        final Map<String, Long> counts = new HashMap<>();

        FakeLimiter() {
            super(null);
        }

        @Override
        public Window hit(String key, Duration window) {
            return new Window(counts.merge(key, 1L, Long::sum), window.toSeconds());
        }

        @Override
        public Window peek(String key, Duration window) {
            return new Window(counts.getOrDefault(key, 0L), window.toSeconds());
        }

        @Override
        public void reset(String key) {
            counts.remove(key);
        }
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ── Login ───────────────────────────────────────────────────────────────

    @Test
    void locksAnAccountAfterFiveWrongPasswordsAndClearsOnSuccess() {
        FakeLimiter limiter = new FakeLimiter();
        LoginAttemptService attempts = new LoginAttemptService(limiter);
        for (int i = 0; i < 5; i++) {
            attempts.checkAllowed("Alice", "1.1.1.1");
            attempts.failed("Alice", "1.1.1.1");
        }
        assertThatThrownBy(() -> attempts.checkAllowed(" alice ", "2.2.2.2"))
                .isInstanceOf(AppException.class).hasMessageContaining("15 phút");
        attempts.checkAllowed("bob", "1.1.1.1");          // another account from the same address is fine
        attempts.succeeded("alice");
        attempts.checkAllowed("alice", "1.1.1.1");
        assertThat(limiter.counts.keySet()).noneMatch(k -> k.contains("alice") || k.contains("1.1.1.1"));
    }

    @Test
    void locksAnAddressAfterThirtyWrongPasswords() {
        LoginAttemptService attempts = new LoginAttemptService(new FakeLimiter());
        for (int i = 0; i < 30; i++) attempts.failed("user" + i, "9.9.9.9");
        assertThatThrownBy(() -> attempts.checkAllowed("someone", "9.9.9.9")).isInstanceOf(AppException.class);
        attempts.checkAllowed("someone", "8.8.8.8");
    }

    // ── Filter ──────────────────────────────────────────────────────────────

    private static MockHttpServletResponse call(RateLimitFilter filter, String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("5.5.5.5");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static void signIn(String name, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(name, null, List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    void limitsSpeechScoringPerUser() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new FakeLimiter());
        signIn("learner", "ROLE_USER");
        for (int i = 0; i < 20; i++) {
            assertThat(call(filter, "POST", "/api/placement/7/speaking").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = call(filter, "POST", "/api/listening/videos/3/segments/0/shadowing");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
        assertThat(blocked.getContentAsString(StandardCharsets.UTF_8)).contains("\"success\":false").contains("giây");
        signIn("other", "ROLE_USER");
        assertThat(call(filter, "POST", "/api/hskk/attempts/1/answers").getStatus()).isEqualTo(200);
    }

    @Test
    void adminsAreNotLimitedAndOtherPathsAreUntouched() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new FakeLimiter());
        signIn("boss", "ROLE_ADMIN");
        for (int i = 0; i < 30; i++) {
            assertThat(call(filter, "POST", "/api/practice/sessions/1/speaking").getStatus()).isEqualTo(200);
        }
        signIn("learner", "ROLE_USER");
        for (int i = 0; i < 100; i++) {
            assertThat(call(filter, "POST", "/api/learning-path/nodes/1/quiz/check").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void guestsAreCountedByAddressOnPublicReads() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new FakeLimiter());
        for (int i = 0; i < 240; i++) call(filter, "GET", "/api/vocabularies/12");
        assertThat(call(filter, "GET", "/api/listening/library").getStatus()).isEqualTo(429);
    }

    @Test
    void refusesOversizedJsonButNotUploads() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new FakeLimiter());
        MockHttpServletRequest json = new MockHttpServletRequest("PUT", "/api/listening/videos/1/segments");
        json.setContentType("application/json");
        json.setContent(new byte[(int) RateLimitFilter.MAX_JSON_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(json, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(413);

        MockHttpServletRequest upload = new MockHttpServletRequest("POST", "/api/other/upload");
        upload.setContentType("multipart/form-data; boundary=x");
        upload.setContent(new byte[(int) RateLimitFilter.MAX_JSON_BYTES + 1]);
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(upload, ok, new MockFilterChain());
        assertThat(ok.getStatus()).isEqualTo(200);
    }

    @Test
    void wordsTheWaitByItsLength() {
        RateLimitFilter.Rule rule = RateLimitFilter.RULES.get(0);
        assertThat(RateLimitFilter.message(rule, 42)).contains("42 giây");
        assertThat(RateLimitFilter.message(rule, 3000)).contains("50 phút");
        assertThat(RateLimitFilter.message(rule, 20000)).contains("6 giờ");
    }
}
