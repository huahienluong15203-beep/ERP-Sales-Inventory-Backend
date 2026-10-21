package com.erp.backend.controller;

import com.erp.backend.dto.inventory.stockcard.StockCardResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.StockMovementService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * S6-02: Thẻ kho - mọi nghiệp vụ nhập / xuất / chuyển / điều chỉnh theo thời gian, số dư sau từng nghiệp vụ,
 * kèm chứng từ gốc. Quyền như sổ tồn kho; NV kho chỉ xem kho mình được gắn (kiểm ở service).
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT', 'SALES_MANAGER')")
public class StockCardController {

    private final StockMovementService stockMovementService;

    /**
     * Vd: ?warehouseId=1&productId=10&fromDate=2026-10-01&toDate=2026-10-31&type=SALES_DISPATCH&page=0&size=50
     * type: RECEIPT, SALES_DISPATCH, DELIVERY_RETURN, TRANSFER_OUT, TRANSFER_IN, STOCKTAKE_ADJUST (bỏ trống = tất cả).
     */
    @GetMapping("/api/inventory/stock-card")
    public StockCardResponse stockCard(@RequestParam(required = false) Long warehouseId,
                                       @RequestParam(required = false) Long productId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size,
                                       @AuthenticationPrincipal UserDetailsImpl actor) {
        return stockMovementService.stockCard(warehouseId, productId, fromDate, toDate, type, page, size, actor);
    }
}
