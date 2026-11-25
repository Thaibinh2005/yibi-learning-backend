package com.yibi.backend.security.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Per-user (or per-address for guests) limits on the calls that cost the server most: speech scoring (Whisper),
 * adding listening material (YouTube and Whisper), saving and submitting lessons, and the public read endpoints.
 * It also refuses non-multipart bodies over {@link #MAX_JSON_BYTES}. Admins are not limited.
 *
 * <p>It runs after Spring Security, so the signed-in user is known; a request over a limit gets 429 with
 * Retry-After and the usual JSON error body.</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class RateLimitFilter extends OncePerRequestFilter {

    static final long MAX_JSON_BYTES = 4L * 1024 * 1024;

    /** One limit: requests of {@code method} whose path matches, at most {@code limit} per {@code window}. */
    record Rule(String name, String method, Pattern path, int limit, Duration window) {
        boolean matches(String m, String uri) {
            return method.equals(m) && path.matcher(uri).matches();
        }
    }

    static final Pattern SPEECH = Pattern.compile("^/api/(listening/videos/\\d+/segments/\\d+/shadowing"
            + "|learning-path/nodes/\\d+/speak|hskk/attempts/\\d+/answers|placement/\\d+/speaking"
            + "|practice/sessions/\\d+/speaking)$");
    static final Pattern PUBLIC_READ = Pattern.compile(
            "^/api/(listening/library|vocabularies|hanzi|topics|courses|lessons/course)(/.*)?$");

    static final List<Rule> RULES = List.of(
            new Rule("speech-min", "POST", SPEECH, 20, Duration.ofMinutes(1)),
            new Rule("speech-day", "POST", SPEECH, 400, Duration.ofDays(1)),
            new Rule("listen-add", "POST", Pattern.compile("^/api/listening/videos(/upload)?$"), 20, Duration.ofHours(1)),
            new Rule("listen-save", "PUT", Pattern.compile("^/api/listening/videos/\\d+/segments$"), 40, Duration.ofMinutes(1)),
            new Rule("listen-job", "POST", Pattern.compile("^/api/listening/videos/\\d+/(finish|translate|retry)$"), 30, Duration.ofHours(1)),
            new Rule("listen-submit", "POST", Pattern.compile("^/api/listening/videos/\\d+/submit$"), 10, Duration.ofDays(1)),
            new Rule("report", "POST", Pattern.compile("^/api/reports$"), 30, Duration.ofDays(1)),
            new Rule("public-read", "GET", PUBLIC_READ, 240, Duration.ofMinutes(1)));

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contentType = request.getContentType();
        boolean multipart = contentType != null && contentType.toLowerCase().startsWith("multipart/");
        if (!multipart && request.getContentLengthLong() > MAX_JSON_BYTES) {
            reject(response, 413, 0, "Dữ liệu gửi lên quá lớn.");
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
        boolean admin = signedIn && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) {
            String who = signedIn ? "u:" + auth.getName() : "ip:" + request.getRemoteAddr();
            for (Rule rule : RULES) {
                if (!rule.matches(request.getMethod(), request.getRequestURI())) continue;
                RateLimiter.Window w = limiter.hit("rl:" + rule.name() + ":" + who, rule.window());
                if (w.count() > rule.limit()) {
                    reject(response, 429, w.resetSeconds(), message(rule, w.resetSeconds()));
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    static String message(Rule rule, long seconds) {
        if (seconds <= 90) return "Bạn thao tác quá nhanh. Hãy thử lại sau " + Math.max(1, seconds) + " giây.";
        if (seconds <= 2 * 3600) return "Bạn đã dùng hết lượt cho chức năng này. Hãy thử lại sau " + ((seconds + 59) / 60) + " phút.";
        return "Bạn đã dùng hết lượt hôm nay cho chức năng này. Hãy thử lại sau " + ((seconds + 3599) / 3600) + " giờ.";
    }

    private static void reject(HttpServletResponse response, int status, long retryAfter, String message) throws IOException {
        response.setStatus(status);
        if (retryAfter > 0) response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"success\":false,\"message\":\"" + message.replace("\"", "'") + "\",\"timestamp\":\""
                + LocalDateTime.now() + "\"}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
