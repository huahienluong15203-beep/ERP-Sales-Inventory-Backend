package com.erp.backend.service;

import com.erp.backend.dto.payment.PaymentReminderLogResponse;
import com.erp.backend.dto.payment.PaymentReminderRunResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.Invoice;
import com.erp.backend.entity.Notification;
import com.erp.backend.entity.PaymentReminderLog;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.InvoiceRepository;
import com.erp.backend.repository.NotificationRepository;
import com.erp.backend.repository.PaymentReminderLogRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * S7-08: Dịch vụ nhắc hạn thanh toán hoá đơn tự động cho Đại lý.
 * - Gửi email và thông báo trong ứng dụng trước hạn 3 ngày và khi quá hạn.
 * - Tác vụ định kỳ chạy mỗi ngày 1 lần.
 * - Tiêu chí: Không gửi trùng nhiều lần cho cùng một hoá đơn trong cùng một ngày.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReminderService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final InvoiceRepository invoiceRepository;
    private final PaymentReminderLogRepository reminderLogRepository;
    private final NotificationRepository notificationRepository;
    private final EmailService emailService;
    private final CustomerRepository customerRepository;

    Clock clock = Clock.system(VN_ZONE);

    /**
     * Quét và gửi nhắc hạn thanh toán cho toàn bộ hoá đơn chưa thanh toán hết.
     * @param simulatedDate Ngày giả lập (nếu null thì lấy ngày hiện tại từ clock) phục vụ kiểm thử và chạy thực tế.
     */
    @Transactional
    public PaymentReminderRunResponse scanAndSendReminders(LocalDate simulatedDate) {
        LocalDate today = simulatedDate != null ? simulatedDate : LocalDate.now(clock);
        // Chỉ 1 lượt quét chạy tại 1 thời điểm (cronjob và chạy tay cùng lúc) để không gửi trùng email / thông báo
        if (!reminderLogRepository.tryLockReminderRun()) {
            log.warn("S7-08: Đang có lượt quét nhắc hạn khác chạy, bỏ qua lượt này");
            return new PaymentReminderRunResponse(today, 0, 0, 0, 0, 0,
                    "Đang có lượt quét nhắc hạn khác chạy, lượt này được bỏ qua để không gửi trùng");
        }
        List<Invoice> unpaidInvoices = invoiceRepository.findUnpaidIssuedInvoicesWithCustomer();

        int scanned = unpaidInvoices.size();
        int sentBeforeDue = 0;
        int sentOverdue = 0;
        int skipped = 0;
        int errors = 0;

        log.info("S7-08: Bắt đầu quét nhắc hạn thanh toán ngày {}: tìm thấy {} hoá đơn còn nợ", today, scanned);

        for (Invoice inv : unpaidInvoices) {
            try {
                if (inv.getRemainingAmount() == null || inv.getRemainingAmount().compareTo(BigDecimal.ZERO) <= 0
                        || Invoice.PAYMENT_STATUS_PAID.equals(inv.getPaymentStatus())) {
                    continue;
                }

                LocalDate dueDate = inv.calculateDueDate();
                long daysDiff = ChronoUnit.DAYS.between(today, dueDate);

                String reminderType = null;
                boolean isOverdue = false;

                if (daysDiff == 3) {
                    reminderType = PaymentReminderLog.REMINDER_BEFORE_DUE_3_DAYS;
                    isOverdue = false;
                } else if (daysDiff < 0) {
                    reminderType = PaymentReminderLog.REMINDER_OVERDUE;
                    isOverdue = true;
                }

                if (reminderType == null) {
                    // Không phải mốc trước 3 ngày và cũng chưa quá hạn -> không gửi
                    continue;
                }

                // Tiêu chí: Không gửi trùng nhiều lần cho cùng một hoá đơn trong cùng một ngày
                if (reminderLogRepository.existsByInvoiceIdAndReminderDate(inv.getId(), today)) {
                    skipped++;
                    log.debug("S7-08: Hoá đơn {} đã được gửi nhắc nợ trong ngày {}, bỏ qua chống spam", inv.getCode(), today);
                    continue;
                }

                Customer customer = inv.getCustomer();
                String customerName = customer != null ? customer.getName() : inv.getCustomerName();
                String customerCode = customer != null ? customer.getCode() : inv.getCustomerCode();
                Long customerId = customer != null ? customer.getId() : null;
                String recipientEmail = customer != null ? customer.getEmail() : null;

                // Xác định tài khoản user của Đại lý để hiển thị chuông thông báo
                Long targetUserId = null;
                if (customer != null) {
                    if (customer.getPortalUser() != null) {
                        targetUserId = customer.getPortalUser().getId();
                    } else if (customer.getUser() != null) {
                        targetUserId = customer.getUser().getId();
                    }
                }

                // 1. Tạo thông báo trong ứng dụng (In-app Notification)
                String notifTitle = isOverdue
                        ? "[Cảnh báo quá hạn] Hoá đơn " + inv.getCode() + " đã quá hạn thanh toán"
                        : "[Nhắc hạn thanh toán] Hoá đơn " + inv.getCode() + " sắp đến hạn thanh toán (còn 3 ngày)";

                String notifContent = isOverdue
                        ? String.format("Hoá đơn %s (nợ còn lại: %s) đã quá hạn %d ngày (hạn trả: %s). Vui lòng thanh toán để không bị khoá đặt đơn mới.",
                                inv.getCode(), formatVnd(inv.getRemainingAmount()), Math.abs(daysDiff), dueDate)
                        : String.format("Hoá đơn %s (nợ còn lại: %s) sẽ đến hạn thanh toán vào ngày %s (còn 3 ngày). Vui lòng sắp xếp thanh toán.",
                                inv.getCode(), formatVnd(inv.getRemainingAmount()), dueDate);

                Notification notification = Notification.builder()
                        .userId(targetUserId)
                        .customerId(customerId)
                        .customerCode(customerCode)
                        .title(notifTitle)
                        .content(notifContent)
                        .notificationType(isOverdue ? Notification.TYPE_PAYMENT_OVERDUE : Notification.TYPE_PAYMENT_DUE_SOON)
                        .referenceType("INVOICE")
                        .referenceId(inv.getId())
                        .referenceCode(inv.getCode())
                        .isRead(false)
                        .createdAt(LocalDateTime.now(clock))
                        .build();

                notificationRepository.save(notification);

                // 2. Ghi nhật ký TRƯỚC khi gửi email để "giữ chỗ" hoá đơn trong ngày (chống gửi trùng)
                PaymentReminderLog reminderLog = PaymentReminderLog.builder()
                        .invoiceId(inv.getId())
                        .invoiceCode(inv.getCode())
                        .customerId(customerId != null ? customerId : 0L)
                        .customerCode(customerCode != null ? customerCode : "")
                        .customerName(customerName)
                        .reminderType(reminderType)
                        .reminderDate(today)
                        .dueDate(dueDate)
                        .daysDifference(daysDiff)
                        .emailSent(false)
                        .inAppSent(true)
                        .recipientEmail(recipientEmail)
                        .sentAt(LocalDateTime.now(clock))
                        .build();
                reminderLogRepository.save(reminderLog);

                // 3. Gửi Email thông báo; gửi được thì đánh dấu trên nhật ký (bản ghi đang được quản lý trong transaction)
                if (StringUtils.hasText(recipientEmail)) {
                    try {
                        emailService.sendPaymentDueReminderEmail(
                                recipientEmail,
                                customerName,
                                inv.getCode(),
                                inv.getRemainingAmount(),
                                dueDate,
                                daysDiff,
                                isOverdue
                        );
                        reminderLog.setEmailSent(true);
                    } catch (Exception ex) {
                        log.warn("S7-08: Không thể gửi email nhắc nợ tới {}: {}", recipientEmail, ex.getMessage());
                    }
                }

                if (isOverdue) {
                    sentOverdue++;
                } else {
                    sentBeforeDue++;
                }
            } catch (Exception e) {
                errors++;
                log.error("S7-08: Lỗi khi xử lý nhắc hạn hoá đơn {}: {}", inv.getCode(), e.getMessage());
            }
        }

        String msg = String.format("Quét hoàn tất ngày %s: %d hoá đơn nợ, nhắc trước hạn %d, nhắc quá hạn %d, bỏ qua trùng %d, lỗi %d",
                today, scanned, sentBeforeDue, sentOverdue, skipped, errors);
        log.info("S7-08: {}", msg);

        return new PaymentReminderRunResponse(today, scanned, sentBeforeDue, sentOverdue, skipped, errors, msg);
    }

    /** Lịch sử nhắc nợ của 1 hoá đơn: NV kinh doanh chỉ xem được hoá đơn của đại lý mình phụ trách (khác -> 404). */
    @Transactional(readOnly = true)
    public List<PaymentReminderLogResponse> getLogsForInvoice(Long invoiceId, UserDetailsImpl actor) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy hoá đơn"));
        CustomerAccess.checkCanAccess(actor, invoice.getCustomer(), customerRepository);
        return reminderLogRepository.findByInvoiceIdOrderBySentAtDesc(invoiceId).stream()
                .map(PaymentReminderService::toLogResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentReminderLogResponse> getLogsByDate(LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now(clock);
        return reminderLogRepository.findByReminderDateOrderBySentAtDesc(target).stream()
                .map(PaymentReminderService::toLogResponse).toList();
    }

    static PaymentReminderLogResponse toLogResponse(PaymentReminderLog l) {
        return new PaymentReminderLogResponse(l.getId(), l.getInvoiceId(), l.getInvoiceCode(), l.getCustomerId(),
                l.getCustomerCode(), l.getCustomerName(), l.getReminderType(), l.getReminderDate(), l.getDueDate(),
                l.getDaysDifference(), l.isEmailSent(), l.isInAppSent(), l.getRecipientEmail(), l.getSentAt());
    }

    private static String formatVnd(BigDecimal amount) {
        if (amount == null) return "0 đ";
        DecimalFormat df = new DecimalFormat("#,### đ", new DecimalFormatSymbols(Locale.GERMANY));
        return df.format(amount);
    }
}
