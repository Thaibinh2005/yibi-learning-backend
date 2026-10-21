package com.yibi.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds jwt.* properties from application.yml to this bean.
 * Requires @ConfigurationPropertiesScan on main class.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** HS256 secret — must be ≥ 32 characters (256 bits) */
    private String secret;

    /** Access token expiration in milliseconds (default: 15 min) */
    private long accessTokenExpiration = 900_000L;

    /** Refresh token expiration in milliseconds (default: 7 days) */
    private long refreshTokenExpiration = 604_800_000L;
}
