package com.yibi.backend.controller.account;

import com.yibi.backend.dto.account.AccountDtos.AccountProfile;
import com.yibi.backend.dto.account.AccountDtos.ChangePasswordRequest;
import com.yibi.backend.dto.account.AccountDtos.UpdateProfileRequest;
import com.yibi.backend.dto.auth.AuthResponse;
import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.security.RefreshCookie;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.AccountService;
import com.yibi.backend.service.AvatarService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import org.springframework.web.bind.annotation.*;

/**
 * The signed-in learner's account (any authenticated user; see SecurityConfig's anyRequest().authenticated()).
 *
 * GET   /api/account           → profile shown on the account page
 * PATCH /api/account/profile   → change the display name
 * POST  /api/account/password  → change or set the password; other sessions are signed out
 * POST  /api/account/avatar    → upload a picture (multipart field "file")
 * DELETE /api/account/avatar   → remove it; the Google picture or the initial shows again
 */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final AvatarService avatarService;
    private final SecurityUtils securityUtils;
    private final RefreshCookie refreshCookie;

    @GetMapping
    public ResponseEntity<ApiResponse<AccountProfile>> profile() {
        return ResponseEntity.ok(ApiResponse.success(accountService.profile(securityUtils.getCurrentUser())));
    }

    @PatchMapping("/profile")
    public ResponseEntity<ApiResponse<AccountProfile>> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        AccountProfile profile = accountService.updateProfile(securityUtils.getCurrentUser(), request);
        return ResponseEntity.ok(ApiResponse.success("Đã lưu thông tin.", profile));
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AccountProfile>> uploadAvatar(@RequestParam("file") MultipartFile file) throws IOException {
        var user = avatarService.upload(securityUtils.getCurrentUser(), file);
        return ResponseEntity.ok(ApiResponse.success("Đã cập nhật ảnh đại diện.", accountService.profile(user)));
    }

    @DeleteMapping("/avatar")
    public ResponseEntity<ApiResponse<AccountProfile>> removeAvatar() {
        var user = avatarService.remove(securityUtils.getCurrentUser());
        return ResponseEntity.ok(ApiResponse.success("Đã gỡ ảnh đại diện.", accountService.profile(user)));
    }

    /** The answer carries new tokens: the refresh token replaces this browser's cookie, the rest goes in the body. */
    @PostMapping("/password")
    public ResponseEntity<ApiResponse<AuthResponse>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        AuthResponse response = accountService.changePassword(securityUtils.getCurrentUser(), request);
        return ResponseEntity.ok()
                .header(RefreshCookie.header(), refreshCookie.issue(response))
                .body(ApiResponse.success("Đã đổi mật khẩu.", response));
    }
}
