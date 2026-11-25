package com.yibi.backend.security;

import com.yibi.backend.config.JwtProperties;
import com.yibi.backend.dto.auth.AuthResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshCookieTest {

    private static RefreshCookie cookie(boolean secure) {
        JwtProperties jwt = new JwtProperties();
        return new RefreshCookie(jwt, secure, "Lax");
    }

    @Test
    void movesTheRefreshTokenFromTheBodyIntoAnHttpOnlyCookie() {
        AuthResponse response = AuthResponse.builder().accessToken("acc").refreshToken("ref-123").build();
        String header = cookie(false).issue(response);
        assertThat(response.getRefreshToken()).isNull();
        assertThat(response.getAccessToken()).isEqualTo("acc");
        assertThat(header).startsWith("yibi_refresh=ref-123").contains("HttpOnly").contains("Path=/api/auth")
                .contains("SameSite=Lax").contains("Max-Age=604800").doesNotContain("Secure");
        assertThat(cookie(true).issue(AuthResponse.builder().refreshToken("x").build())).contains("Secure");
    }

    @Test
    void clearsAndReads() {
        assertThat(cookie(false).clear()).startsWith("yibi_refresh=;").contains("Max-Age=0");
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(cookie(false).read(request)).isNull();
        request.setCookies(new Cookie("other", "1"), new Cookie(RefreshCookie.NAME, "tok"));
        assertThat(cookie(false).read(request)).isEqualTo("tok");
    }
}
