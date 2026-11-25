package com.yibi.backend.dto.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.UUID;

/** Requests and answers of the account page (/api/account). */
public final class AccountDtos {

    private AccountDtos() {}

    /**
     * What the account page shows. The username is the sign-in name and cannot change;
     * {@code passwordSet} is false for a Google account that has never had a password of its own.
     */
    public record AccountProfile(
            UUID id,
            String username,
            String displayName,
            String avatarUrl,
            /** True when the shown picture is one the learner uploaded (so it can be removed). */
            boolean avatarUploaded,
            String email,
            String role,
            Integer hskLevel,
            boolean googleLinked,
            boolean passwordSet,
            LocalDateTime createdAt,
            LocalDateTime lastLogin) {}

    public record UpdateProfileRequest(
            @NotBlank(message = "Vui lòng nhập tên hiển thị")
            @Size(max = 50, message = "Tên hiển thị tối đa 50 ký tự")
            String displayName) {}

    /** {@code currentPassword} may be empty only while the account has no password of its own. */
    public record ChangePasswordRequest(
            String currentPassword,
            @NotBlank(message = "Vui lòng nhập mật khẩu mới")
            @Size(min = 6, max = 100, message = "Mật khẩu mới cần từ 6 đến 100 ký tự")
            String newPassword) {}
}
