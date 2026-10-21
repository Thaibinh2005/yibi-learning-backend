package com.yibi.backend.entity;

/**
 * RBAC Roles for Yibi Platform.
 *
 * Usage in Spring Security:
 *   - @PreAuthorize("hasRole('ADMIN')")     → ROLE_ADMIN only
 *   - @PreAuthorize("hasRole('USER')")      → ROLE_USER (and ADMIN)
 *   - @PreAuthorize("isAuthenticated()")    → any authenticated user
 *
 * Spring strips the "ROLE_" prefix automatically when using hasRole().
 */
public enum Role {
    ROLE_USER,
    ROLE_ADMIN
}
