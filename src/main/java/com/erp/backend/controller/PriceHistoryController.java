package com.erp.backend.controller;

import com.erp.backend.dto.pricing.PriceHistoryResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.service.PriceHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * S3-02: Tra cứu lịch sử thay đổi giá. Chỉ có API xem: lịch sử không sửa, không xoá được.
 * Quyền: Admin, QL kinh doanh, NV kinh doanh, Kế toán.
 */
@RestController
@RequestMapping("/api/price-history")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP', 'ACCOUNTANT')")
public class PriceHistoryController {

    private final PriceHistoryService priceHistoryService;

    /** Vd: ?productSku=SP-COCA-330&customerGroup=DEALER_LEVEL_1&fromDate=2026-09-01&toDate=2026-10-31&page=0&size=20 */
    @GetMapping
    public PageResponse<PriceHistoryResponse> search(
            @RequestParam(required = false) String productSku,
            @RequestParam(required = false) Long priceListId,
            @RequestParam(required = false) String customerGroup,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return priceHistoryService.search(productSku, priceListId, customerGroup, fromDate, toDate, page, size);
    }
}
