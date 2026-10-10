package com.erp.backend.config;

import com.erp.backend.service.OrderReservationExpiryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * S5-06: Mỗi giờ (phút thứ 5) quét đơn quá hạn giữ chỗ và tự nhả tồn.
 * Tắt bằng erp.order.reservation-expiry.enabled=false.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "erp.order.reservation-expiry.enabled", havingValue = "true", matchIfMissing = true)
public class OrderReservationExpiryJob {

    private final OrderReservationExpiryService expiryService;

    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Ho_Chi_Minh")
    public void run() {
        runOnce();
    }

    /** @return số đơn đã nhả giữ chỗ trong lần quét này */
    public int runOnce() {
        List<Long> ids = expiryService.findExpiredOrderIds();
        int released = 0;
        for (Long id : ids) {
            try {
                if (expiryService.expireOne(id)) {
                    released++;
                }
            } catch (RuntimeException e) {
                // Một đơn lỗi (vd khoá dòng tồn bị tranh chấp) không chặn các đơn còn lại; lần quét sau sẽ thử lại
                log.warn("S5-06: Không nhả được giữ chỗ của đơn id={}: {}", id, e.getMessage());
            }
        }
        if (released > 0) {
            log.info("S5-06: Đã tự nhả giữ chỗ {} / {} đơn quá hạn", released, ids.size());
        }
        return released;
    }
}
