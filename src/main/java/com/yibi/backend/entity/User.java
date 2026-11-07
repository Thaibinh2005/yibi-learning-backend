package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * User entity — maps to `users` table.
 * Supports RBAC via {@link Role} enum.
 * Password is always stored as BCrypt hash, never plain text.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    /**
     * BCrypt-hashed password. Never expose this in DTOs.
     */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /**
     * RBAC role. Stored as VARCHAR in DB (e.g., "ROLE_USER").
     * Spring Security uses this to enforce @PreAuthorize.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.ROLE_USER;

    /**
     * Soft-delete flag. Locked users cannot log in.
     * Admin can toggle via /api/admin/users/{id}/toggle-status.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    /**
     * Set after the Placement Test is completed.
     * Null means the user hasn't taken placement test yet.
     */
    @Column(name = "hsk_level")
    private Integer hskLevel;

    /** Null until the first-sign-in survey is done; accounts that predate the survey are set by migration. */
    @Column(name = "onboarding_completed_at")
    private LocalDateTime onboardingCompletedAt;

    /** The level the learner chose in the survey, 0 (none) to 9. */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "self_reported_level")
    private Integer selfReportedLevel;

    /** BASIC (start from HSK 1) or PLACEMENT (take the test). */
    @Column(name = "start_mode", length = 16)
    private String startMode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    /** The stable id of the linked Google account; null if the learner has not signed in with Google. */
    @Column(name = "google_sub")
    private String googleSub;
}
