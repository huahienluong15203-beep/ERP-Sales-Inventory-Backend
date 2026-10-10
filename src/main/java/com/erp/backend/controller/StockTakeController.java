package com.erp.backend.controller;

import com.erp.backend.dto.stocktake.StockTakeCountRequest;
import com.erp.backend.dto.stocktake.StockTakeCreateRequest;
import com.erp.backend.dto.stocktake.StockTakeReasonRequest;
import com.erp.backend.dto.stocktake.StockTakeResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.StockTakeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * S5-08: Phiếu kiểm kê kho.
 * Xem: NV kho, QL kho, Admin, Kế toán. Tạo / nhập số đếm: NV kho, QL kho (Admin tạo được).
 * Chốt: CHỈ Quản lý kho. Huỷ phiếu nháp: QL kho, Admin.
 */
@RestController
@RequestMapping("/api/inventory/stocktakes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT')")
public class StockTakeController {

    private final StockTakeService stockTakeService;

    /** Vd: ?warehouseId=1&status=DRAFT&fromDate=2026-10-01&toDate=2026-10-31&page=0&size=20 */
    @GetMapping
    public PageResponse<StockTakeResponse> search(@RequestParam(required = false) Long warehouseId,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @AuthenticationPrincipal UserDetailsImpl actor) {
        return stockTakeService.search(warehouseId, status, fromDate, toDate, page, size, actor);
    }

    @GetMapping("/{id}")
    public StockTakeResponse get(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl actor) {
        return stockTakeService.get(id, actor);
    }

    /** Tạo phiếu: chụp số tồn sổ của mọi mặt hàng trong kho (hoặc nhóm hàng đã chọn) tại thời điểm tạo. */
    @PostMapping
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN')")
    public ResponseEntity<StockTakeResponse> create(@Valid @RequestBody StockTakeCreateRequest request,
                                                    @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stockTakeService.create(request, actor));
    }

    /** Nhập số đếm thực tế, hệ thống tự tính chênh lệch từng dòng. */
    @PutMapping("/{id}/counts")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER')")
    public StockTakeResponse updateCounts(@PathVariable Long id, @Valid @RequestBody StockTakeCountRequest request,
                                          @AuthenticationPrincipal UserDetailsImpl actor) {
        return stockTakeService.updateCounts(id, request, actor);
    }

    /** Chốt kiểm kê: điều chỉnh tồn về số thực đếm, bắt buộc lý do. Chỉ Quản lý kho. */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('WH_MANAGER')")
    public StockTakeResponse close(@PathVariable Long id, @Valid @RequestBody StockTakeReasonRequest request,
                                   @AuthenticationPrincipal UserDetailsImpl actor) {
        return stockTakeService.close(id, request.getReason(), actor);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'ADMIN')")
    public StockTakeResponse cancel(@PathVariable Long id, @Valid @RequestBody StockTakeReasonRequest request,
                                    @AuthenticationPrincipal UserDetailsImpl actor) {
        return stockTakeService.cancel(id, request.getReason(), actor);
    }
}
