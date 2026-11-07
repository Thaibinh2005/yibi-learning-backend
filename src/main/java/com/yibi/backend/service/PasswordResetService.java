package com.yibi.backend.service;

import com.yibi.backend.exception.AppException;
import com.yibi.backend.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Forgot password. The answer to a request never says whether the email has an account, and the emailed token works
 * once, for a short time, and exists in the database only as a hash.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    static final int VALID_MINUTES = 30;
    private static final Duration ONE_REQUEST_PER_EMAIL = Duration.ofMinutes(1);
    private static final Duration ADDRESS_WINDOW = Duration.ofHours(1);
    private static final int REQUESTS_PER_ADDRESS = 20;

    private final JdbcTemplate jdbc;
    private final MailService mailService;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final TokenBlacklistService tokens;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public void request(String email, String clientAddress) {
        if (!mailService.isConfigured()) {
            throw new AppException("Chức năng gửi email chưa được cấu hình. Hãy liên hệ quản trị viên.", HttpStatus.SERVICE_UNAVAILABLE);
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (!allowed(normalized, clientAddress)) return;

        List<Map<String, Object>> accounts = jdbc.queryForList(
                "SELECT id, username, email FROM users WHERE lower(email) = ? AND is_active = TRUE LIMIT 1", normalized);
        if (accounts.isEmpty()) return;

        Map<String, Object> account = accounts.get(0);
        UUID userId = (UUID) account.get("id");
        String token = newToken();
        jdbc.update("UPDATE password_reset_tokens SET used_at = NOW() WHERE user_id = ? AND used_at IS NULL", userId);
        jdbc.update("""
                INSERT INTO password_reset_tokens (user_id, token_hash, expires_at)
                VALUES (?, ?, NOW() + make_interval(mins => ?))
                """, userId, hash(token), VALID_MINUTES);

        String link = UriComponentsBuilder.fromUriString(frontendUrl).path("/reset-password").queryParam("token", token).build().toUriString();
        mailService.sendPasswordReset((String) account.get("email"), (String) account.get("username"), link, VALID_MINUTES);
    }

    @Transactional
    public void reset(String token, String newPassword) {
        List<Map<String, Object>> used = jdbc.queryForList("""
                UPDATE password_reset_tokens t SET used_at = NOW()
                FROM users u
                WHERE t.user_id = u.id AND t.token_hash = ? AND t.used_at IS NULL AND t.expires_at > NOW() AND u.is_active = TRUE
                RETURNING u.id, u.username
                """, hash(token.trim()));
        if (used.isEmpty()) {
            throw new BadRequestException("Liên kết đặt lại mật khẩu đã hết hạn hoặc đã được dùng. Hãy yêu cầu liên kết mới.");
        }
        UUID userId = (UUID) used.get(0).get("id");
        jdbc.update("UPDATE users SET password_hash = ?, updated_at = NOW() WHERE id = ?", passwordEncoder.encode(newPassword), userId);
        jdbc.update("UPDATE password_reset_tokens SET used_at = NOW() WHERE user_id = ? AND used_at IS NULL", userId);
        // Whoever knew the old password is signed out on their next token refresh.
        tokens.revokeAllRefreshTokens((String) used.get(0).get("username"));
        log.info("Password reset for user {}", userId);
    }

    /** Over a limit the request is dropped silently, so the answer stays the same for every address. */
    private boolean allowed(String email, String clientAddress) {
        String emailKey = "auth:reset:email:" + hash(email);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(emailKey, "1", ONE_REQUEST_PER_EMAIL))) return false;
        String addressKey = "auth:reset:addr:" + hash(String.valueOf(clientAddress));
        Long count = redis.opsForValue().increment(addressKey);
        if (count != null && count == 1) redis.expire(addressKey, ADDRESS_WINDOW);
        return count == null || count <= REQUESTS_PER_ADDRESS;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
