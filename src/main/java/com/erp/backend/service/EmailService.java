package com.erp.backend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @org.springframework.beans.factory.annotation.Value("${erp.app.mailFrom:${spring.mail.username:okluon123pk@gmail.com}}")
    private String mailFrom;

    /**
     * Gửi email đặt lại mật khẩu - CHẠY NGẦM (@Async) để API không phải đợi máy chủ Gmail.
     */
    @Async
    public void sendPasswordResetEmail(String toEmail, String resetLink) {
        // Luôn in link ra Console để tiện cho lập trình viên test ngay tại máy
        System.out.println("\n=======================================================");
        System.out.println(">>> [EMAIL PHỤC VỤ TEST] ĐẶT LẠI MẬT KHẨU <<<");
        System.out.println("Gửi tới: " + toEmail);
        System.out.println("Link đặt lại (30 phút): " + resetLink);
        System.out.println("=======================================================\n");

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(mailFrom, "ERP Sales & Inventory");
            helper.setTo(toEmail);
            helper.setSubject("[ERP Sales & Inventory] Yêu cầu đặt lại mật khẩu");

            String htmlContent = """
                        <div style="font-family: Arial, sans-serif; max-width: 600px; margin: auto; padding: 20px; border: 1px solid #e2e8f0; border-radius: 8px;">
                            <h2 style="color: #1e3a8a;">Hệ Thống ERP Sales & Inventory</h2>
                            <p>Xin chào,</p>
                            <p>Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản liên kết với email này.</p>
                            <p>Vui lòng bấm vào nút bên dưới để tiến hành đổi mật khẩu mới:</p>
                            <div style="text-align: center; margin: 30px 0;">
                                <a href="%s" style="background-color: #2563eb; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; font-weight: bold; display: inline-block;">
                                    Đặt Lại Mật Khẩu
                                </a>
                            </div>
                            <p style="color: #64748b; font-size: 14px;">• <i>Lưu ý: Liên kết này chỉ có hiệu lực trong <b>30 phút</b> và chỉ sử dụng được <b>1 lần duy nhất</b>.</i></p>
                            <p style="color: #64748b; font-size: 14px;">Nếu bạn không yêu cầu hành động này, vui lòng bỏ qua email.</p>
                            <hr style="border: none; border-top: 1px solid #e2e8f0; margin: 20px 0;">
                            <p style="color: #94a3b8; font-size: 12px; text-align: center;">Đội ngũ kỹ thuật ERP Sales & Inventory System</p>
                        </div>
                    """
                    .formatted(resetLink);

            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("Đã gửi email đặt lại mật khẩu thành công tới: {}", toEmail);
        } catch (Exception e) {
            log.error("Lỗi khi gửi email qua SMTP: {}", e.getMessage());
            // Không ném lỗi ra ngoài để đảm bảo bảo mật và tránh crash ứng dụng khi dev
        }
    }
}
