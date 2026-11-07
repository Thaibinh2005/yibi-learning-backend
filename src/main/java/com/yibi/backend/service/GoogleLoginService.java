package com.yibi.backend.service;

import com.yibi.backend.dto.auth.AuthResponse;
import com.yibi.backend.entity.Role;
import com.yibi.backend.entity.User;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sign-in with Google through the OAuth 2.0 authorization code flow. The client secret stays on the server.
 * Google sends the browser back here; the browser then gets only a one-time code, swapped for our own tokens, so no
 * token ever appears in an address, where history and logs would keep it.
 */
@Slf4j
@Service
public class GoogleLoginService {

    private static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(1);
    private static final Set<String> GOOGLE_ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    private static final int MAX_USERNAME = 50;

    /** The account Google vouches for. */
    record GoogleIdentity(String subject, String email, String name) {}

    private final UserRepository users;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final TokenBlacklistService tokens;
    private final JdbcTemplate jdbc;
    private final RestClient http = RestClient.create();
    private final SecureRandom random = new SecureRandom();
    private volatile JwtDecoder decoder;

    @Value("${app.google.client-id:}") private String clientId;
    @Value("${app.google.client-secret:}") private String clientSecret;
    @Value("${app.google.redirect-uri}") private String redirectUri;
    @Value("${app.google.auth-uri}") private String authUri;
    @Value("${app.google.token-uri}") private String tokenUri;
    @Value("${app.google.jwks-uri}") private String jwksUri;
    @Value("${app.frontend-url}") private String frontendUrl;

    public GoogleLoginService(UserRepository users, AuthService authService, PasswordEncoder passwordEncoder,
                              StringRedisTemplate redis, TokenBlacklistService tokens, JdbcTemplate jdbc) {
        this.users = users;
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
        this.tokens = tokens;
        this.jdbc = jdbc;
    }

    /** Where the browser goes when the Google button is pressed. */
    public String startUrl(String returnTo) {
        if (clientId.isBlank() || clientSecret.isBlank()) return loginError("not_configured");
        String state = randomToken();
        String nonce = randomToken();
        redis.opsForValue().set(stateKey(state), nonce + "|" + safeReturnTo(returnTo), STATE_LIFETIME);
        return UriComponentsBuilder.fromUriString(authUri)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("prompt", "select_account")
                .build().encode().toUriString();
    }

    /** Where the browser goes after Google answers; every failure ends on the login page with a reason. */
    public String finishUrl(String code, String state, String error) {
        if (error != null) return loginError("access_denied".equals(error) ? "cancelled" : "failed");
        String saved = state == null ? null : redis.opsForValue().getAndDelete(stateKey(state));
        if (saved == null || code == null) return loginError("expired");
        int split = saved.indexOf('|');
        try {
            GoogleIdentity identity = identityFrom(decoder().decode(exchangeForIdToken(code)), saved.substring(0, split));
            User user = accountFor(identity);
            if (!user.isActive()) return loginError("disabled");
            String loginCode = randomToken();
            redis.opsForValue().set(codeKey(loginCode), user.getId().toString(), CODE_LIFETIME);
            return UriComponentsBuilder.fromUriString(frontendUrl).path("/auth/google/callback")
                    .queryParam("code", loginCode)
                    .queryParam("returnTo", saved.substring(split + 1))
                    .build().encode().toUriString();
        } catch (UnverifiedEmailException ex) {
            return loginError("unverified");
        } catch (RuntimeException ex) {
            log.warn("Google sign-in failed: {}", ex.getMessage());
            return loginError("failed");
        }
    }

    public AuthResponse exchange(String code) {
        String id = code == null ? null : redis.opsForValue().getAndDelete(codeKey(code));
        if (id == null) throw new BadRequestException("Phiên đăng nhập Google đã hết hạn. Hãy thử lại.");
        User user = users.findById(UUID.fromString(id)).orElseThrow(() -> new BadRequestException("Không tìm thấy tài khoản."));
        return authService.issueTokens(user);
    }

    @SuppressWarnings("unchecked")
    private String exchangeForIdToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");
        Map<String, Object> answer = http.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        Object idToken = answer == null ? null : answer.get("id_token");
        if (!(idToken instanceof String value)) throw new IllegalStateException("Google answered without an id_token");
        return value;
    }

    private JwtDecoder decoder() {
        if (decoder == null) {
            NimbusJwtDecoder built = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
            built.setJwtValidator(validators(clientId));
            decoder = built;
        }
        return decoder;
    }

    /** The token must be current, issued by Google, and meant for this application. */
    static OAuth2TokenValidator<Jwt> validators(String clientId) {
        OAuth2TokenValidator<Jwt> issuer = jwt -> GOOGLE_ISSUERS.contains(jwt.getClaimAsString("iss"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong issuer", null));
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(clientId)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuer, audience);
    }

    /** Ties the token to the sign-in started here, and accepts only an address Google has verified. */
    static GoogleIdentity identityFrom(Jwt token, String expectedNonce) {
        if (expectedNonce == null || !expectedNonce.equals(token.getClaimAsString("nonce"))) {
            throw new IllegalStateException("Nonce does not match");
        }
        Object verified = token.getClaim("email_verified");
        String email = token.getClaimAsString("email");
        if (email == null || !(Boolean.TRUE.equals(verified) || "true".equals(String.valueOf(verified)))) {
            throw new UnverifiedEmailException();
        }
        return new GoogleIdentity(token.getSubject(), email.trim().toLowerCase(Locale.ROOT), token.getClaimAsString("name"));
    }

    private User accountFor(GoogleIdentity identity) {
        User known = users.findByGoogleSub(identity.subject()).orElse(null);
        if (known != null) return known;

        List<UUID> sameEmail = jdbc.queryForList("SELECT id FROM users WHERE lower(email) = ? LIMIT 1", UUID.class, identity.email());
        if (!sameEmail.isEmpty()) {
            User user = users.findById(sameEmail.get(0)).orElseThrow();
            user.setGoogleSub(identity.subject());
            // Sign-up does not verify email, so someone else may have registered this address first. The old password
            // stops working and old sessions end; the real owner can set a new password through "Quên mật khẩu".
            user.setPasswordHash(passwordEncoder.encode(randomToken()));
            tokens.revokeAllRefreshTokens(user.getUsername());
            log.info("Linked Google sign-in to existing account {}", user.getId());
            return users.save(user);
        }

        User created = users.save(User.builder()
                .username(uniqueUsername(identity.email()))
                .email(identity.email())
                .passwordHash(passwordEncoder.encode(randomToken()))
                .googleSub(identity.subject())
                .role(Role.ROLE_USER)
                .isActive(true)
                .build());
        log.info("New account from Google sign-in: {}", created.getId());
        return created;
    }

    private String uniqueUsername(String email) {
        String base = usernameBase(email);
        String candidate = base;
        while (users.existsByUsername(candidate)) {
            candidate = base.substring(0, Math.min(base.length(), MAX_USERNAME - 5)) + "_" + (1000 + random.nextInt(9000));
        }
        return candidate;
    }

    /** The part before @, kept to what a username allows (letters, digits, underscore; 3 to 50 long). */
    static String usernameBase(String email) {
        String local = email.substring(0, Math.max(email.indexOf('@'), 0)).replaceAll("[^a-zA-Z0-9_]", "");
        if (local.length() < 3) local = "user" + local;
        return local.substring(0, Math.min(local.length(), MAX_USERNAME));
    }

    /** Only a path on this site, so a crafted link cannot send the learner somewhere else after sign-in. */
    static String safeReturnTo(String returnTo) {
        boolean local = returnTo != null && returnTo.startsWith("/") && !returnTo.startsWith("//") && !returnTo.contains("\\");
        return local ? returnTo : "/dashboard";
    }

    private String loginError(String reason) {
        return UriComponentsBuilder.fromUriString(frontendUrl).path("/login").queryParam("googleError", reason).build().toUriString();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String stateKey(String state) {
        return "auth:google:state:" + PasswordResetService.hash(state);
    }

    private static String codeKey(String code) {
        return "auth:google:code:" + PasswordResetService.hash(code);
    }

    static final class UnverifiedEmailException extends RuntimeException {}
}
