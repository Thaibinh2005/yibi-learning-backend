package com.yibi.backend.security;

import com.yibi.backend.config.JwtProperties;
import com.yibi.backend.dto.auth.AuthResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The refresh token lives in an HttpOnly cookie scoped to /api/auth, so page scripts (and anything injected into them)
 * can never read it; the short-lived access token stays in the page's memory. Responses that sign a user in move the
 * refresh token from the JSON body into this cookie.
 *
 * <p>{@code app.auth.cookie-secure} must be true behind HTTPS (the prod profile sets it), and
 * {@code app.auth.cookie-same-site} stays Lax unless the frontend is served from another site.</p>
 */
@Component
public class RefreshCookie {

    public static final String NAME = "yibi_refresh";
    static final String PATH = "/api/auth";

    private final Duration maxAge;
    private final boolean secure;
    private final String sameSite;

    public RefreshCookie(JwtProperties jwt,
                         @Value("${app.auth.cookie-secure:false}") boolean secure,
                         @Value("${app.auth.cookie-same-site:Lax}") String sameSite) {
        this.maxAge = Duration.ofMillis(jwt.getRefreshTokenExpiration());
        this.secure = secure;
        this.sameSite = sameSite;
    }

    /** Puts the response's refresh token into the cookie and removes it from the body; returns the Set-Cookie value. */
    public String issue(AuthResponse response) {
        String token = response.getRefreshToken();
        response.setRefreshToken(null);
        return cookie(token, maxAge).toString();
    }

    /** A Set-Cookie value that deletes the cookie. */
    public String clear() {
        return cookie("", Duration.ZERO).toString();
    }

    /** The token from the cookie, or null. */
    public String read(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (NAME.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) return c.getValue();
        }
        return null;
    }

    private ResponseCookie cookie(String value, Duration age) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH)
                .maxAge(age)
                .build();
    }

    public static String header() {
        return HttpHeaders.SET_COOKIE;
    }
}
