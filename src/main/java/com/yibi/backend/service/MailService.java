package com.yibi.backend.service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;

/** Sends in the background, so a slow mail server cannot show from the response time whether an account exists. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;
    private final TaskExecutor applicationTaskExecutor;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    @Value("${spring.mail.properties.mail.smtp.auth:true}")
    private boolean authRequired;

    @Value("${app.mail.from:}")
    private String from;

    public boolean isConfigured() {
        if (from.isBlank() && username.isBlank()) return false;
        return !authRequired || (!username.isBlank() && !password.isBlank());
    }

    public void sendPasswordReset(String to, String name, String link, int validMinutes) {
        // The link itself never goes into the log: it would let anyone reading logs take the account.
        send(to, "Đặt lại mật khẩu Yibi", passwordResetText(name, link, validMinutes), passwordResetHtml(name, link, validMinutes), "Password reset");
    }

    public void sendRegisterOtp(String to, String name, String code, int validMinutes) {
        // Like the reset link, the code never goes into the log.
        send(to, code + " là mã xác thực đăng ký Yibi", registerOtpText(name, code, validMinutes), registerOtpHtml(name, code, validMinutes), "Registration code");
    }

    private void send(String to, String subject, String text, String html, String label) {
        applicationTaskExecutor.execute(() -> {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
                helper.setFrom(new InternetAddress(from.isBlank() ? username : from, "Yibi", StandardCharsets.UTF_8.name()));
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(text, html);
                mailSender.send(message);
                log.info("{} email sent", label);
            } catch (Exception ex) {
                log.error("{} email failed: {}", label, ex.getMessage());
            }
        });
    }

    static String registerOtpText(String name, String code, int validMinutes) {
        return """
                Chào %s,

                Mã xác thực đăng ký tài khoản Yibi của bạn là:

                %s

                Mã có hiệu lực trong %d phút. Không chia sẻ mã này với bất kỳ ai.
                Nếu không phải bạn đăng ký, hãy bỏ qua email này.
                """.formatted(name, code, validMinutes);
    }

    static String registerOtpHtml(String name, String code, int validMinutes) {
        String safeName = HtmlUtils.htmlEscape(name);
        return """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:0 auto;color:#292524;line-height:1.6">
                  <h2 style="color:#C8322B;margin-bottom:8px">Xác thực email đăng ký Yibi</h2>
                  <p>Chào <b>%s</b>,</p>
                  <p>Nhập mã dưới đây vào trang đăng ký để hoàn tất tạo tài khoản:</p>
                  <p style="margin:24px 0;font-size:32px;font-weight:bold;letter-spacing:8px;color:#C8322B;font-family:'Courier New',monospace">%s</p>
                  <p style="font-size:14px;color:#57534e">Mã có hiệu lực trong %d phút. Không chia sẻ mã này với bất kỳ ai.<br>Nếu không phải bạn đăng ký, hãy bỏ qua email này.</p>
                </div>
                """.formatted(safeName, code, validMinutes);
    }

    static String passwordResetText(String name, String link, int validMinutes) {
        return """
                Chào %s,

                Yibi vừa nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn. Mở liên kết sau để đặt mật khẩu mới:

                %s

                Liên kết có hiệu lực trong %d phút và chỉ dùng được một lần.
                Nếu không phải bạn yêu cầu, hãy bỏ qua email này. Mật khẩu của bạn vẫn giữ nguyên.
                """.formatted(name, link, validMinutes);
    }

    static String passwordResetHtml(String name, String link, int validMinutes) {
        String safeName = HtmlUtils.htmlEscape(name);
        String safeLink = HtmlUtils.htmlEscape(link);
        return """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:0 auto;color:#292524;line-height:1.6">
                  <h2 style="color:#C8322B;margin-bottom:8px">Đặt lại mật khẩu Yibi</h2>
                  <p>Chào <b>%s</b>,</p>
                  <p>Yibi vừa nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn.</p>
                  <p style="margin:24px 0"><a href="%s" style="background:#C8322B;color:#ffffff;padding:12px 24px;border-radius:10px;text-decoration:none;font-weight:bold">Đặt mật khẩu mới</a></p>
                  <p style="font-size:14px;color:#57534e">Liên kết có hiệu lực trong %d phút và chỉ dùng được một lần.<br>Nếu không phải bạn yêu cầu, hãy bỏ qua email này. Mật khẩu của bạn vẫn giữ nguyên.</p>
                  <p style="font-size:12px;color:#78716c;word-break:break-all">Nếu nút không mở được, hãy sao chép liên kết này: %s</p>
                </div>
                """.formatted(safeName, safeLink, validMinutes, safeLink);
    }
}
