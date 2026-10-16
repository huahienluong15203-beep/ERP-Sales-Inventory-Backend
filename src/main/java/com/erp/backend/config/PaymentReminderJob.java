package com.erp.backend.config;

import com.erp.backend.dto.payment.PaymentReminderRunResponse;
import com.erp.backend.service.PaymentReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * S7-08 / SCRUM-262: Tác vụ định kỳ mỗi ngày 1 lần quét gửi email và thông báo chuông nhắc nợ hoá đơn cho Đại lý.
 * Mặc định chạy lúc 08:00 sáng mỗi ngày (zone Asia/Ho_Chi_Minh).
 * Có thể bật/tắt bằng erp.payment.reminder-job.enabled=false hoặc tuỳ chỉnh cron bằng erp.payment.reminder-job.cron.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "erp.payment.reminder-job.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentReminderJob {

    private final PaymentReminderService paymentReminderService;

    @Scheduled(cron = "${erp.payment.reminder-job.cron:0 0 8 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void run() {
        log.info("S7-08: Cronjob kích hoạt quét nhắc hạn thanh toán hoá đơn hàng ngày");
        runOnce(null);
    }

    public PaymentReminderRunResponse runOnce(LocalDate simulatedDate) {
        return paymentReminderService.scanAndSendReminders(simulatedDate);
    }
}
