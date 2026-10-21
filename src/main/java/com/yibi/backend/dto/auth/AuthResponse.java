package com.yibi.backend.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Authentication response returned on successful login or register.
 * Contains JWT access token + refresh token + user info.
 *
 * Security note: refresh token is returned in response body here.
 * For production, consider moving refreshToken to an HttpOnly cookie.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("refresh_token")
    private String refreshToken;

    @JsonProperty("token_type")
    @Builder.Default
    private String tokenType = "Bearer";

    @JsonProperty("expires_in")
    private long expiresIn;  // Seconds until access token expires

    // User info embedded in response (avoids extra /me call on login)
    private UUID userId;
    private String username;
    private String email;
    private String role;
    private Integer hskLevel;
}
