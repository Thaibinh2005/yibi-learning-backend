package com.yibi.backend.service;

import com.yibi.backend.config.JwtProperties;
import com.yibi.backend.dto.auth.AuthResponse;
import com.yibi.backend.dto.auth.LoginRequest;
import com.yibi.backend.dto.auth.RegisterRequest;
import com.yibi.backend.entity.Role;
import com.yibi.backend.entity.User;
import com.yibi.backend.repository.UserRepository;
import com.yibi.backend.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Authentication Service — handles register, login, refresh, logout.
 *
 * Token Strategy:
 * - Access Token: Short-lived JWT (15 min), contains username + role + userId
 * - Refresh Token: Long-lived UUID (7 days), stored in Redis, rotated on each use
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;
    private final JwtProperties jwtProperties;

    // ── Register ──────────────────────────────────────────────

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check for duplicates
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username '" + request.getUsername() + "' is already taken");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email '" + request.getEmail() + "' is already registered");
        }

        // Create user entity
        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.ROLE_USER)
                .isActive(true)
                .build();

        user = userRepository.save(user);
        log.info("New user registered: {} ({})", user.getUsername(), user.getEmail());

        return buildAuthResponse(user);
    }

    // ── Login ─────────────────────────────────────────────────

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Spring Security handles credential verification + account lock check
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsernameOrEmail(),
                        request.getPassword()
                )
        );

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();

        // Load full user entity to get additional fields (userId, role, hskLevel)
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found after authentication"));

        // Update last login timestamp
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        log.info("User logged in: {}", user.getUsername());
        return buildAuthResponse(user);
    }

    // ── Refresh Token ─────────────────────────────────────────

    @Transactional
    public AuthResponse refreshToken(String refreshToken) {
        // Validate refresh token exists in Redis
        String username = tokenBlacklistService.getUsernameFromRefreshToken(refreshToken);
        if (username == null) {
            throw new IllegalArgumentException("Refresh token is invalid or expired");
        }

        // Load user
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        if (!user.isActive()) {
            throw new IllegalArgumentException("Account is disabled");
        }

        // Rotate refresh token (delete old, issue new)
        tokenBlacklistService.deleteRefreshToken(refreshToken);

        log.info("Token refreshed for user: {}", username);
        return buildAuthResponse(user);
    }

    // ── Logout ────────────────────────────────────────────────

    public void logout(String accessToken, String refreshToken) {
        // Blacklist access token for its remaining lifetime
        if (accessToken != null && jwtTokenProvider.validateToken(accessToken)) {
            long expirationMs = jwtTokenProvider.extractExpiration(accessToken).getTime();
            long remainingMs = expirationMs - System.currentTimeMillis();
            if (remainingMs > 0) {
                tokenBlacklistService.blacklistToken(accessToken, remainingMs);
            }
        }

        // Delete refresh token from Redis
        if (refreshToken != null) {
            tokenBlacklistService.deleteRefreshToken(refreshToken);
        }

        log.info("User logged out successfully");
    }

    // ── Private Helpers ───────────────────────────────────────

    private AuthResponse buildAuthResponse(User user) {
        // Build UserDetails for token generation
        org.springframework.security.core.userdetails.UserDetails userDetails =
                org.springframework.security.core.userdetails.User.builder()
                        .username(user.getUsername())
                        .password(user.getPasswordHash())
                        .authorities(user.getRole().name())
                        .build();

        // Generate access token
        String accessToken = jwtTokenProvider.generateAccessToken(
                userDetails,
                user.getId().toString(),
                user.getRole().name()
        );

        // Generate and store refresh token
        String refreshTokenKey = jwtTokenProvider.generateRefreshTokenKey();
        tokenBlacklistService.storeRefreshToken(
                refreshTokenKey,
                user.getUsername(),
                jwtProperties.getRefreshTokenExpiration()
        );

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshTokenKey)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenExpirationSeconds())
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole().name())
                .hskLevel(user.getHskLevel())
                .build();
    }
}
