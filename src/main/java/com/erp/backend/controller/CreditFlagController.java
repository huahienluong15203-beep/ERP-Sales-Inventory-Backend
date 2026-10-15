package com.erp.backend.controller;

import com.erp.backend.dto.credit.CreditFlagRunResponse;
import com.erp.backend.dto.credit.FlaggedCustomerResponse;
import com.erp.backend.service.CreditFlagRunner;
import com.erp.backend.service.CreditFlagService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * S7-04: Cờ cảnh báo công nợ.
 * - Xem đại lý đang bị cờ: Kế toán, Admin, QL kinh doanh.
 * - Chạy quét ngay (không chờ lịch hằng ngày) / đánh giá lại 1 đại lý: Kế toán, Admin.
 */
@RestController
@RequestMapping("/api/credit-flags")
@RequiredArgsConstructor
public class CreditFlagController {

    private final CreditFlagRunner runner;
    private final CreditFlagService creditFlagService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN', 'SALES_MANAGER')")
    public List<FlaggedCustomerResponse> flagged() {
        return runner.flagged();
    }

    /** Chạy quét cờ công nợ cho mọi đại lý ngay bây giờ (giống tác vụ hằng ngày). */
    @PostMapping("/evaluate")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN')")
    public CreditFlagRunResponse evaluateAll() {
        return runner.runAll("MANUAL");
    }

    /** Đánh giá lại cờ của 1 đại lý. */
    @PostMapping("/evaluate/{customerId}")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN')")
    public CreditFlagService.Result evaluateOne(@PathVariable Long customerId) {
        return creditFlagService.reevaluate(customerId, "MANUAL");
    }
}
