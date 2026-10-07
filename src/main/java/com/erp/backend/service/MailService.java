package com.erp.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Gửi email hệ thống.
 * Nếu chưa cấu hình spring.mail.host, JavaMailSender sẽ không tồn tại -> chỉ ghi log (dùng khi dev),
 * để việc tạo tài khoản không bị hỏng chỉ vì thiếu SMTP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${erp.app.frontendUrl:http://localhost:5173}")
    private String frontendUrl;

    @Value("${erp.app.mailFrom:no-reply@erp.local}")
    private String mailFrom;

    /** Hệ thống đã cấu hình máy chủ gửi mail (SMTP) hay chưa. */
    public boolean isConfigured() {
        return mailSenderProvider.getIfAvailable() != null;
    }

    /**
     * S1-08: Gửi email kích hoạt kèm mật khẩu tạm - CHẠY NGẦM (@Async).
     * API tạo tài khoản trả kết quả ngay, không phải đợi máy chủ Gmail phản hồi.
     */
    @Async
    public void sendAccountCreatedEmail(String toEmail, String fullName, String username, String tempPassword) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("[DEV] Chưa cấu hình SMTP - không gửi được email. Tài khoản: {} | Mật khẩu tạm: {}",
                    username, tempPassword);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(toEmail);
        message.setSubject("[ERP Bán hàng & Kho] Tài khoản của bạn đã được tạo");
        message.setText("""
                Xin chào %s,

                Quản trị viên đã tạo tài khoản cho bạn trên Hệ thống Bán hàng & Kho.

                Tên đăng nhập: %s
                Mật khẩu tạm:  %s

                Đăng nhập tại: %s/login
                Bạn sẽ được yêu cầu đổi mật khẩu ngay ở lần đăng nhập đầu tiên.

                Nếu bạn không yêu cầu tài khoản này, vui lòng bỏ qua email.
                """.formatted(fullName, username, tempPassword, frontendUrl));

        try {
            sender.send(message);
            log.info("Đã gửi email kích hoạt tài khoản '{}' tới {}", username, toEmail);
        } catch (MailException e) {
            log.error("Gửi email kích hoạt cho {} thất bại: {}", username, e.getMessage());
        }
    }
}
