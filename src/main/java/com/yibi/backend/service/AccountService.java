package com.yibi.backend.service;

import com.yibi.backend.dto.account.AccountDtos.AccountProfile;
import com.yibi.backend.dto.account.AccountDtos.ChangePasswordRequest;
import com.yibi.backend.dto.account.AccountDtos.UpdateProfileRequest;
import com.yibi.backend.dto.auth.AuthResponse;
import com.yibi.backend.entity.User;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** The signed-in learner's own account: profile details and password. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenBlacklistService tokens;
    private final AuthService authService;

    public AccountProfile profile(User user) {
        return new AccountProfile(
                user.getId(),
                user.getUsername(),
                displayNameOf(user),
                user.shownAvatarUrl(),
                user.getAvatarKey() != null,
                user.getEmail(),
                user.getRole().name(),
                user.getHskLevel(),
                user.getGoogleSub() != null,
                user.isPasswordSet(),
                user.getCreatedAt(),
                user.getLastLogin());
    }

    @Transactional
    public AccountProfile updateProfile(User user, UpdateProfileRequest request) {
        // Collapse runs of spaces so "  Bình   Nguyễn " is stored as "Bình Nguyễn".
        String name = request.displayName().trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) throw new BadRequestException("Vui lòng nhập tên hiển thị");
        user.setDisplayName(name);
        return profile(users.save(user));
    }

    /**
     * Changes (or, for a Google account without one, sets) the password. Every other session ends:
     * their refresh tokens are revoked. The caller gets fresh tokens so this browser stays signed in.
     */
    @Transactional
    public AuthResponse changePassword(User user, ChangePasswordRequest request) {
        if (user.isPasswordSet()) {
            if (!StringUtils.hasText(request.currentPassword())
                    || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
                throw new BadRequestException("Mật khẩu hiện tại không đúng.");
            }
            if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
                throw new BadRequestException("Mật khẩu mới cần khác mật khẩu hiện tại.");
            }
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setPasswordSet(true);
        users.save(user);
        tokens.revokeAllRefreshTokens(user.getUsername());
        log.info("Password changed for user {}", user.getId());
        return authService.issueTokens(user);
    }

    private static String displayNameOf(User user) {
        return StringUtils.hasText(user.getDisplayName()) ? user.getDisplayName() : user.getUsername();
    }
}
