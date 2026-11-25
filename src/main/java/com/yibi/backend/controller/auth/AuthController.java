package com.yibi.backend.controller.auth;

import com.yibi.backend.dto.auth.LoginRequest;
import com.yibi.backend.dto.auth.AuthResponse;
import com.yibi.backend.dto.auth.RefreshTokenRequest;
import com.yibi.backend.dto.auth.RegisterRequest;
import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.service.AuthService;
import com.yibi.backend.service.MailService;
import com.yibi.backend.service.RegistrationOtpService;
import com.yibi.backend.security.RefreshCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication Controller — Public endpoints.
 *
 * All routes under /api/auth/** are PERMITTED in SecurityConfig (no JWT required).
 *
 * Endpoints:
 * POST /api/auth/register/send-otp   → Check the sign-up form and email a 6-digit code
 * POST /api/auth/register/resend-otp → Email a new code (at most once a minute)
 * POST /api/auth/register/verify     → Create the account when the code is right, and sign in
 * POST /api/auth/register    → Old one-step sign-up, only while email is not configured (local development)
 * POST /api/auth/login       → Authenticate, receive access + refresh tokens
 * POST /api/auth/refresh     → Rotate refresh token, get new access token
 * POST /api/auth/logout      → Blacklist access token, delete refresh token
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RegistrationOtpService registrationOtp;
    private final MailService mailService;
    private final RefreshCookie refreshCookie;

    /** A sign-in answer: the refresh token goes into the HttpOnly cookie, the rest into the body. */
    private ResponseEntity<ApiResponse<AuthResponse>> signedIn(HttpStatus status, String message, AuthResponse response) {
        String cookie = refreshCookie.issue(response);
        return ResponseEntity.status(status).header(RefreshCookie.header(), cookie).body(ApiResponse.success(message, response));
    }

    public record ResendOtpRequest(@NotBlank(message = "Vui lòng nhập email") @Email(message = "Email không hợp lệ") String email) {}

    public record VerifyOtpRequest(
            @NotBlank(message = "Vui lòng nhập email") @Email(message = "Email không hợp lệ") String email,
            @NotBlank(message = "Vui lòng nhập mã xác thực") @Pattern(regexp = "^\\s*\\d{6}\\s*$", message = "Mã xác thực gồm 6 chữ số") String code) {}

    /** Step 1 of sign-up: nothing is created yet, a code goes to the email. */
    @PostMapping("/register/send-otp")
    public ResponseEntity<ApiResponse<Void>> sendRegisterOtp(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        registrationOtp.start(request, http.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.success("Đã gửi mã xác thực tới email của bạn.", null));
    }

    @PostMapping("/register/resend-otp")
    public ResponseEntity<ApiResponse<Void>> resendRegisterOtp(@Valid @RequestBody ResendOtpRequest request, HttpServletRequest http) {
        registrationOtp.resend(request.email(), http.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.success("Đã gửi lại mã xác thực.", null));
    }

    /** Step 2 of sign-up: the right code creates the account and signs it in. */
    @PostMapping("/register/verify")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyRegisterOtp(@Valid @RequestBody VerifyOtpRequest request) {
        AuthResponse response = registrationOtp.verify(request.email(), request.code());
        return signedIn(HttpStatus.CREATED, "Đăng ký thành công! Chào mừng bạn đến với Yibi", response);
    }

    /**
     * Register a new user account.
     *
     * POST /api/auth/register
     * Body: { "username": "...", "email": "...", "password": "..." }
     * Response 201: { "access_token": "...", "refresh_token": "...", ... }
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request) {

        // Once email works, every new account must prove its address through /register/send-otp.
        if (mailService.isConfigured()) {
            throw new BadRequestException("Hãy xác thực email bằng mã OTP để đăng ký.");
        }
        AuthResponse response = authService.register(request);
        return signedIn(HttpStatus.CREATED, "Registration successful! Welcome to Yibi 🇨🇳", response);
    }

    /**
     * Login with username/email + password.
     *
     * POST /api/auth/login
     * Body: { "usernameOrEmail": "...", "password": "..." }
     * Response 200: { "access_token": "...", "refresh_token": "...", "role": "ROLE_USER", ... }
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {

        AuthResponse response = authService.login(request, http.getRemoteAddr());
        return signedIn(HttpStatus.OK, "Login successful", response);
    }

    /**
     * Refresh the access token using a valid refresh token.
     * The old refresh token is invalidated (rotation).
     *
     * POST /api/auth/refresh
     * Body: { "refreshToken": "uuid-string" }
     * Response 200: New AuthResponse with fresh access + refresh tokens
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @RequestBody(required = false) RefreshTokenRequest request, HttpServletRequest http) {

        // The cookie normally; a token in the body only for a browser still holding one from before the cookie.
        String token = refreshCookie.read(http);
        if (token == null && request != null && StringUtils.hasText(request.getRefreshToken())) token = request.getRefreshToken();
        if (token == null) throw new com.yibi.backend.exception.UnauthorizedException("Phiên đăng nhập đã hết. Hãy đăng nhập lại.");
        AuthResponse response = authService.refreshToken(token);
        return signedIn(HttpStatus.OK, "Token refreshed", response);
    }

    /**
     * Logout: invalidate tokens server-side.
     *
     * POST /api/auth/logout
     * Header: Authorization: Bearer <access_token>
     * Body (optional): { "refreshToken": "uuid-string" }
     * Response 200: { "success": true, "message": "Logged out successfully" }
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) RefreshTokenRequest request, HttpServletRequest http) {

        String accessToken = null;
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            accessToken = authHeader.substring(7);
        }

        String refreshToken = refreshCookie.read(http);
        if (refreshToken == null && request != null) refreshToken = request.getRefreshToken();
        authService.logout(accessToken, refreshToken);

        return ResponseEntity.ok().header(RefreshCookie.header(), refreshCookie.clear())
                .body(ApiResponse.success("Logged out successfully", null));
    }
}
