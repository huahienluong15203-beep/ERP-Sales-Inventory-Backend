package com.erp.backend.config;

import com.erp.backend.dto.credit.CreditFlagRunResponse;
import com.erp.backend.service.CreditFlagRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * S7-04: Mỗi ngày 1 lần (mặc định 00:15) đánh giá lại cờ công nợ mọi đại lý đang hoạt động.
 * Tắt bằng erp.credit.flag-job.enabled=false; đổi giờ bằng erp.credit.flag-job.cron.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "erp.credit.flag-job.enabled", havingValue = "true", matchIfMissing = true)
public class CreditFlagJob {

    private final CreditFlagRunner runner;

    @Scheduled(cron = "${erp.credit.flag-job.cron:0 15 0 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void run() {
        runOnce();
    }

    public CreditFlagRunResponse runOnce() {
        return runner.runAll("DAILY_JOB");
    }
}
