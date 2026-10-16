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

    /**
     * S7-08: Gửi email nhắc hạn thanh toán cho Đại lý trước 3 ngày hoặc khi quá hạn.
     */
    @Async
    public void sendPaymentDueReminderEmail(String toEmail, String customerName, String invoiceCode,
                                           java.math.BigDecimal remainingAmount, java.time.LocalDate dueDate,
                                           long daysDiff, boolean isOverdue) {
        if (!org.springframework.util.StringUtils.hasText(toEmail)) {
            log.info("S7-08: Đại lý {} không có email để gửi thông báo nhắc nợ hoá đơn {}", customerName, invoiceCode);
            return;
        }

        String formattedAmount = formatVnd(remainingAmount);
        String subject = isOverdue
                ? "[CẢNH BÁO QUÁ HẠN] Hoá đơn " + invoiceCode + " đã quá hạn thanh toán"
                : "[NHẮC HẠN THANH TOÁN] Hoá đơn " + invoiceCode + " sắp đến hạn thanh toán";

        System.out.println("\n=======================================================");
        System.out.println(">>> [EMAIL PHỤC VỤ TEST] " + subject + " <<<");
        System.out.println("Gửi tới: " + toEmail + " (" + customerName + ")");
        System.out.println("Hoá đơn: " + invoiceCode + " | Nợ còn lại: " + formattedAmount + " | Hạn: " + dueDate);
        System.out.println("Trạng thái: " + (isOverdue ? "QUÁ HẠN " + Math.abs(daysDiff) + " ngày" : "Còn " + daysDiff + " ngày đến hạn"));
        System.out.println("=======================================================\n");

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(mailFrom, "ERP Sales & Inventory");
            helper.setTo(toEmail);
            helper.setSubject("[ERP Sales & Inventory] " + subject);

            String statusAlertBox = isOverdue
                    ? """
                        <div style="background-color: #fee2e2; border-left: 4px solid #ef4444; padding: 12px 16px; margin: 20px 0; border-radius: 4px;">
                            <strong style="color: #b91c1c;">CẢNH BÁO KHOÁ ĐƠN:</strong>
                            <p style="margin: 4px 0 0; color: #7f1d1d; font-size: 13px;">Hoá đơn đã quá hạn %d ngày. Theo chính sách công nợ, hệ thống có thể tạm khoá tính năng đặt đơn mới cho đến khi khoản nợ được thanh toán.</p>
                        </div>
                    """.formatted(Math.abs(daysDiff))
                    : """
                        <div style="background-color: #fef3c7; border-left: 4px solid #f59e0b; padding: 12px 16px; margin: 20px 0; border-radius: 4px;">
                            <strong style="color: #92400e;">NHẮC NHỞ HẠN THANH TOÁN:</strong>
                            <p style="margin: 4px 0 0; color: #78350f; font-size: 13px;">Hoá đơn của Quý khách sẽ đến hạn thanh toán trong %d ngày tới (%s). Vui lòng sắp xếp thanh toán để không bị gián đoạn đơn hàng.</p>
                        </div>
                    """.formatted(daysDiff, dueDate);

            String htmlContent = """
                        <div style="font-family: Arial, sans-serif; max-width: 600px; margin: auto; padding: 24px; border: 1px solid #e2e8f0; border-radius: 12px; background-color: #ffffff;">
                            <div style="border-bottom: 2px solid #2563eb; padding-bottom: 12px; margin-bottom: 20px;">
                                <h2 style="color: #1e3a8a; margin: 0; font-size: 20px;">Hệ Thống Phân Phối ERP Sales & Inventory</h2>
                                <p style="color: #64748b; margin: 4px 0 0; font-size: 12px;">Thông báo nhắc hạn công nợ đại lý tự động</p>
                            </div>
                            <p>Kính gửi: <strong>%s</strong>,</p>
                            <p>Hệ thống trân trọng thông báo thông tin thanh toán cho hoá đơn bán hàng của Quý khách:</p>
                            %s
                            <table style="width: 100%%; border-collapse: collapse; margin: 20px 0; font-size: 14px;">
                                <tr>
                                    <td style="padding: 8px 12px; border: 1px solid #e2e8f0; background-color: #f8fafc; width: 40%%;">Mã hoá đơn:</td>
                                    <td style="padding: 8px 12px; border: 1px solid #e2e8f0; font-weight: bold; font-family: monospace;">%s</td>
                                </tr>
                                <tr>
                                    <td style="padding: 8px 12px; border: 1px solid #e2e8f0; background-color: #f8fafc;">Số tiền còn nợ:</td>
                                    <td style="padding: 8px 12px; border: 1px solid #e2e8f0; font-weight: bold; color: #dc2626; font-size: 16px;">%s</td>
                                </tr>
                                <tr>
                                    <td style="padding: 8px 12px; border: 1px solid #e2e8f0; background-color: #f8fafc;">Hạn thanh toán:</td>
                                    <td style="padding: 8px 12px; border: 1px solid #e2e8f0; font-weight: bold;">%s</td>
                                </tr>
                            </table>
                            <p style="color: #475569; font-size: 13px; line-height: 1.5;">
                                Quý khách có thể nộp tiền mặt cho Nhân viên kinh doanh phụ trách tuyến hoặc thanh toán qua chuyển khoản ngân hàng theo thông tin trên hợp đồng.
                            </p>
                            <hr style="border: none; border-top: 1px solid #e2e8f0; margin: 24px 0 16px;">
                            <p style="color: #94a3b8; font-size: 11px; text-align: center; margin: 0;">Email này được gửi tự động từ Hệ thống ERP. Vui lòng không trả lời trực tiếp email này.</p>
                        </div>
                    """
                    .formatted(customerName, statusAlertBox, invoiceCode, formattedAmount, dueDate);

            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("S7-08: Đã gửi email nhắc nợ thành công tới: {}", toEmail);
        } catch (Exception e) {
            log.warn("S7-08: Không thể gửi email nhắc nợ qua SMTP: {}", e.getMessage());
        }
    }

    private String formatVnd(java.math.BigDecimal amount) {
        if (amount == null) return "0 đ";
        java.text.DecimalFormat df = new java.text.DecimalFormat("#,### đ", new java.text.DecimalFormatSymbols(java.util.Locale.GERMANY));
        return df.format(amount);
    }
}
