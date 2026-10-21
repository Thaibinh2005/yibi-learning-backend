package com.yibi.backend.controller.auth;

import com.yibi.backend.dto.auth.LoginRequest;
import com.yibi.backend.dto.auth.AuthResponse;
import com.yibi.backend.dto.auth.RefreshTokenRequest;
import com.yibi.backend.dto.auth.RegisterRequest;
import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.service.AuthService;
import jakarta.validation.Valid;
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
 * POST /api/auth/register    → Create new ROLE_USER account
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

        AuthResponse response = authService.register(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Registration successful! Welcome to Yibi 🇨🇳", response));
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
            @Valid @RequestBody LoginRequest request) {

        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
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
            @Valid @RequestBody RefreshTokenRequest request) {

        AuthResponse response = authService.refreshToken(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Token refreshed", response));
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
            @RequestBody(required = false) RefreshTokenRequest request) {

        String accessToken = null;
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            accessToken = authHeader.substring(7);
        }

        String refreshToken = (request != null) ? request.getRefreshToken() : null;
        authService.logout(accessToken, refreshToken);

        return ResponseEntity.ok(ApiResponse.success("Logged out successfully", null));
    }
}
