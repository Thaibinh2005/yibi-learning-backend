package com.yibi.backend.controller.auth;

import com.yibi.backend.dto.auth.AuthResponse;
import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.service.GoogleLoginService;
import com.yibi.backend.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Forgot password and Google sign-in. Everything under /api/auth is public. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AccountRecoveryController {

    public record ForgotPasswordRequest(@NotBlank(message = "Vui lòng nhập email") @Email(message = "Email không hợp lệ") String email) {}

    public record ResetPasswordRequest(
            @NotBlank(message = "Liên kết không hợp lệ") String token,
            @NotBlank(message = "Vui lòng nhập mật khẩu mới") @Size(min = 6, max = 100, message = "Mật khẩu cần từ 6 đến 100 ký tự") String newPassword) {}

    public record GoogleExchangeRequest(@NotBlank String code) {}

    private final PasswordResetService passwordReset;
    private final GoogleLoginService googleLogin;

    /** The same answer whether or not the email has an account. */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        passwordReset.request(request.email(), http.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.success("Nếu email này đã đăng ký, bạn sẽ nhận được liên kết đặt lại mật khẩu.", null));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordReset.reset(request.token(), request.newPassword());
        return ResponseEntity.ok(ApiResponse.success("Đã đổi mật khẩu. Hãy đăng nhập bằng mật khẩu mới.", null));
    }

    @GetMapping("/google/start")
    public ResponseEntity<Void> googleStart(@RequestParam(required = false) String returnTo) {
        return redirect(googleLogin.startUrl(returnTo));
    }

    @GetMapping("/google/callback")
    public ResponseEntity<Void> googleCallback(@RequestParam(required = false) String code,
                                               @RequestParam(required = false) String state,
                                               @RequestParam(required = false) String error) {
        return redirect(googleLogin.finishUrl(code, state, error));
    }

    @PostMapping("/google/exchange")
    public ResponseEntity<ApiResponse<AuthResponse>> googleExchange(@Valid @RequestBody GoogleExchangeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Login successful", googleLogin.exchange(request.code())));
    }

    private static ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url).build();
    }
}
