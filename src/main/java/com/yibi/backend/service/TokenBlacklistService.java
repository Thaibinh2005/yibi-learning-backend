package com.yibi.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Token Blacklist Service — manages JWT invalidation via Redis.
 *
 * On logout:
 * 1. Access token → added to blacklist with TTL = remaining token lifetime
 * 2. Refresh token UUID → deleted from Redis refresh store
 *
 * On request:
 * - JwtAuthenticationFilter checks isBlacklisted() before trusting any token
 *
 * Redis key format:
 * - Blacklist:  "jwt:blacklist:{full_token}"
 * - Refresh:    "jwt:refresh:{uuid}"  → value: username
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";
    private static final String REFRESH_PREFIX   = "jwt:refresh:";

    private final RedisTemplate<String, String> redisTemplate;

    // ── Blacklist (Access Token Invalidation) ─────────────────

    /**
     * Add an access token to the blacklist with a TTL.
     * After TTL expires, the token would be invalid anyway (expired).
     *
     * @param token     Full JWT string
     * @param ttlMillis Remaining valid time in milliseconds
     */
    public void blacklistToken(String token, long ttlMillis) {
        String key = BLACKLIST_PREFIX + token;
        redisTemplate.opsForValue().set(key, "blacklisted", ttlMillis, TimeUnit.MILLISECONDS);
        log.debug("Token blacklisted with TTL: {}ms", ttlMillis);
    }

    /**
     * Check if an access token has been blacklisted.
     */
    public boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token));
    }

    // ── Refresh Token Store ────────────────────────────────────

    /**
     * Store a refresh token mapping: refreshKey → username.
     *
     * @param refreshKey UUID string (the refresh token sent to client)
     * @param username   Username this token belongs to
     * @param ttlMillis  Expiration in milliseconds (default 7 days)
     */
    public void storeRefreshToken(String refreshKey, String username, long ttlMillis) {
        String key = REFRESH_PREFIX + refreshKey;
        redisTemplate.opsForValue().set(key, username, ttlMillis, TimeUnit.MILLISECONDS);
        log.debug("Stored refresh token for user: {}", username);
    }

    /**
     * Validate a refresh token and return the associated username.
     *
     * @return username if valid, null if expired/not found
     */
    public String getUsernameFromRefreshToken(String refreshKey) {
        return redisTemplate.opsForValue().get(REFRESH_PREFIX + refreshKey);
    }

    /**
     * Delete a refresh token (on logout or rotation).
     */
    public void deleteRefreshToken(String refreshKey) {
        redisTemplate.delete(REFRESH_PREFIX + refreshKey);
        log.debug("Deleted refresh token: {}", refreshKey);
    }

    /**
     * Check if a refresh token exists and is still valid.
     */
    public boolean isRefreshTokenValid(String refreshKey) {
        return getUsernameFromRefreshToken(refreshKey) != null;
    }
}
