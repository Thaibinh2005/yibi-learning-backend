package com.yibi.backend.service;

import com.yibi.backend.dto.auth.AuthResponse;
import com.yibi.backend.dto.auth.RegisterRequest;
import com.yibi.backend.exception.AppException;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Sign-up with an emailed one-time code. Nothing is written to the users table until the code is right:
 * the pending sign-up (username, email, password hash, code hash, wrong tries) lives in Redis for a few minutes.
 * The code itself is never stored or logged, only its SHA-256.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationOtpService {

    static final int VALID_MINUTES = 10;
    static final int MAX_ATTEMPTS = 5;
    static final int RESEND_SECONDS = 60;
    private static final Duration ADDRESS_WINDOW = Duration.ofHours(1);
    private static final int CODES_PER_ADDRESS = 20;

    private final StringRedisTemplate redis;
    private final MailService mailService;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final SecureRandom random = new SecureRandom();

    /** Checks the form, keeps it as a pending sign-up and emails a code. */
    public void start(RegisterRequest request, String clientAddress) {
        requireMail();
        String email = normalize(request.getEmail());
        String username = request.getUsername().trim();
        if (userRepository.existsByUsername(username)) {
            throw new BadRequestException("Tên đăng nhập '" + username + "' đã có người dùng.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BadRequestException("Email này đã được đăng ký. Hãy đăng nhập hoặc dùng chức năng quên mật khẩu.");
        }
        throttle(email, clientAddress);

        String code = newCode();
        String key = pendingKey(email);
        hash().putAll(key, Map.of(
                "username", username,
                "email", email,
                "passwordHash", passwordEncoder.encode(request.getPassword()),
                "codeHash", PasswordResetService.hash(code),
                "attempts", "0"));
        redis.expire(key, Duration.ofMinutes(VALID_MINUTES));
        mailService.sendRegisterOtp(email, username, code, VALID_MINUTES);
    }

    /** A fresh code for a pending sign-up; the old one stops working and the wrong tries start over. */
    public void resend(String rawEmail, String clientAddress) {
        requireMail();
        String email = normalize(rawEmail);
        String key = pendingKey(email);
        Object username = hash().get(key, "username");
        if (username == null) throw expired();
        throttle(email, clientAddress);

        String code = newCode();
        hash().putAll(key, Map.of("codeHash", PasswordResetService.hash(code), "attempts", "0"));
        redis.expire(key, Duration.ofMinutes(VALID_MINUTES));
        mailService.sendRegisterOtp(email, (String) username, code, VALID_MINUTES);
    }

    /** Creates the account and signs it in when the code is right. */
    public AuthResponse verify(String rawEmail, String code) {
        String email = normalize(rawEmail);
        String key = pendingKey(email);
        Map<Object, Object> pending = hash().entries(key);
        if (pending.isEmpty()) throw expired();

        byte[] expected = String.valueOf(pending.get("codeHash")).getBytes(StandardCharsets.UTF_8);
        byte[] given = PasswordResetService.hash(code.trim()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, given)) {
            Long attempts = hash().increment(key, "attempts", 1);
            long left = MAX_ATTEMPTS - (attempts == null ? MAX_ATTEMPTS : attempts);
            if (left <= 0) {
                redis.delete(key);
                throw new BadRequestException("Bạn đã nhập sai mã quá " + MAX_ATTEMPTS + " lần. Hãy đăng ký lại để nhận mã mới.");
            }
            throw new BadRequestException("Mã xác thực không đúng. Bạn còn " + left + " lần thử.");
        }

        // Only the request that removes the key creates the account, so a double submit cannot create two.
        if (!Boolean.TRUE.equals(redis.delete(key))) throw expired();
        return authService.registerVerified(
                (String) pending.get("username"), (String) pending.get("email"), (String) pending.get("passwordHash"));
    }

    private void requireMail() {
        if (!mailService.isConfigured()) {
            throw new AppException("Chức năng gửi email chưa được cấu hình. Hãy liên hệ quản trị viên.", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /** One code per email per minute, and a cap per client address so the form cannot be used to spam mailboxes. */
    private void throttle(String email, String clientAddress) {
        String emailKey = "auth:register:cooldown:" + PasswordResetService.hash(email);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(emailKey, "1", Duration.ofSeconds(RESEND_SECONDS)))) {
            Long wait = redis.getExpire(emailKey);
            throw new AppException("Vui lòng đợi " + (wait == null || wait < 1 ? RESEND_SECONDS : wait)
                    + " giây trước khi yêu cầu mã mới.", HttpStatus.TOO_MANY_REQUESTS);
        }
        String addressKey = "auth:register:addr:" + PasswordResetService.hash(String.valueOf(clientAddress));
        Long count = redis.opsForValue().increment(addressKey);
        if (count != null && count == 1) redis.expire(addressKey, ADDRESS_WINDOW);
        if (count != null && count > CODES_PER_ADDRESS) {
            throw new AppException("Bạn đã yêu cầu quá nhiều mã. Hãy thử lại sau.", HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private HashOperations<String, Object, Object> hash() {
        return redis.opsForHash();
    }

    private String newCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    private static String pendingKey(String email) {
        return "auth:register:pending:" + PasswordResetService.hash(email);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static BadRequestException expired() {
        return new BadRequestException("Mã xác thực đã hết hạn hoặc phiên đăng ký không còn. Hãy đăng ký lại.");
    }
}
