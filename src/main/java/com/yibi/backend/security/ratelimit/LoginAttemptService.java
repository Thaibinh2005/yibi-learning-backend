package com.yibi.backend.security.ratelimit;

import com.yibi.backend.exception.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Stops password guessing: after 5 wrong passwords for one account, or 30 from one address, within 15 minutes, login
 * is refused until the window ends. A successful login clears the account's count. Keys hold hashes, never the
 * username or address itself.
 */
@Service
public class LoginAttemptService {

    static final int MAX_PER_ACCOUNT = 5;
    static final int MAX_PER_ADDRESS = 30;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final RateLimiter limiter;

    public LoginAttemptService(RateLimiter limiter) {
        this.limiter = limiter;
    }

    /** Throws 429 when the account or the address has used up its wrong attempts. */
    public void checkAllowed(String usernameOrEmail, String clientAddress) {
        RateLimiter.Window account = limiter.peek(accountKey(usernameOrEmail), WINDOW);
        RateLimiter.Window address = limiter.peek(addressKey(clientAddress), WINDOW);
        long wait = 0;
        if (account.count() >= MAX_PER_ACCOUNT) wait = account.resetSeconds();
        if (address.count() >= MAX_PER_ADDRESS) wait = Math.max(wait, address.resetSeconds());
        if (wait > 0) {
            throw new AppException("Bạn đã nhập sai mật khẩu quá nhiều lần. Hãy thử lại sau " + minutes(wait) + " phút.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    public void failed(String usernameOrEmail, String clientAddress) {
        limiter.hit(accountKey(usernameOrEmail), WINDOW);
        limiter.hit(addressKey(clientAddress), WINDOW);
    }

    public void succeeded(String usernameOrEmail) {
        limiter.reset(accountKey(usernameOrEmail));
    }

    static String accountKey(String usernameOrEmail) {
        String id = usernameOrEmail == null ? "" : usernameOrEmail.trim().toLowerCase(Locale.ROOT);
        return "auth:login:acct:" + sha256(id);
    }

    static String addressKey(String clientAddress) {
        return "auth:login:addr:" + sha256(String.valueOf(clientAddress));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static long minutes(long seconds) {
        return Math.max(1, (seconds + 59) / 60);
    }
}
