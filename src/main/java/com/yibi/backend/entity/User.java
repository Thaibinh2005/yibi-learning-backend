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

    /** Shown in the app instead of the username; null means "use the username". */
    @Column(name = "display_name", length = 50)
    private String displayName;

    /** Profile picture link, taken from Google at each Google sign-in; null shows the initial. */
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    /** Media storage key of a picture uploaded on the account page; it is shown instead of {@link #avatarUrl}. */
    @Column(name = "avatar_key", length = 80)
    private String avatarKey;

    /** The picture to show: the uploaded one, else the Google one, else null (the initial). */
    public String shownAvatarUrl() {
        if (avatarKey != null) {
            // The version changes with each upload, so a browser never keeps showing the old picture from its cache.
            String file = avatarKey.substring(avatarKey.lastIndexOf('/') + 1);
            return "/api/avatars/" + id + "?v=" + file.substring(0, Math.min(8, file.length()));
        }
        return avatarUrl;
    }

    /**
     * BCrypt-hashed password. Never expose this in DTOs.
     */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** False while the password is a random one from Google sign-in, which the learner has never seen. */
    @Column(name = "password_set", nullable = false)
    @Builder.Default
    private boolean passwordSet = true;

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
