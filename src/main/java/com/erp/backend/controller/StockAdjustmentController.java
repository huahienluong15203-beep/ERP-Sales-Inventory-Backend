package com.erp.backend.controller;

import com.erp.backend.dto.stockadjustment.StockAdjustmentCreateRequest;
import com.erp.backend.dto.stockadjustment.StockAdjustmentDecisionRequest;
import com.erp.backend.dto.stockadjustment.StockAdjustmentResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.StockAdjustmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * S8-03: REST API quản lý phiếu điều chỉnh tồn kho có phê duyệt.
 * - Lập phiếu điều chỉnh: Quản lý kho, Thủ kho, Admin.
 * - Phê duyệt / Từ chối: Chỉ Quản lý kho, Admin (người lập và người duyệt không được là một).
 */
@RestController
@RequestMapping("/api/stock-adjustments")
@RequiredArgsConstructor
public class StockAdjustmentController {

    private final StockAdjustmentService stockAdjustmentService;

    /**
     * Tiêu chí 1 & 2: Lập phiếu điều chỉnh tồn kho (Dùng cho hàng hỏng, hàng mất, hàng hết hạn).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'WAREHOUSE', 'ADMIN')")
    public ResponseEntity<StockAdjustmentResponse> createAdjustment(
            @Valid @RequestBody StockAdjustmentCreateRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        StockAdjustmentResponse response = stockAdjustmentService.createAdjustment(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Tiêu chí 3 & 4: Quản lý kho phê duyệt phiếu điều chỉnh tồn kho.
     * Người lập và người duyệt không được là một người.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'ADMIN')")
    public ResponseEntity<StockAdjustmentResponse> approveAdjustment(
            @PathVariable Long id,
            @RequestBody(required = false) StockAdjustmentDecisionRequest decision,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        StockAdjustmentResponse response = stockAdjustmentService.approveAdjustment(id, decision, actor);
        return ResponseEntity.ok(response);
    }

    /**
     * Quản lý kho từ chối duyệt phiếu điều chỉnh tồn kho.
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'ADMIN')")
    public ResponseEntity<StockAdjustmentResponse> rejectAdjustment(
            @PathVariable Long id,
            @RequestBody(required = false) StockAdjustmentDecisionRequest decision,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        StockAdjustmentResponse response = stockAdjustmentService.rejectAdjustment(id, decision, actor);
        return ResponseEntity.ok(response);
    }

    /**
     * Huỷ phiếu điều chỉnh tồn kho (khi còn ở trạng thái PENDING_APPROVAL).
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'WAREHOUSE', 'ADMIN')")
    public ResponseEntity<StockAdjustmentResponse> cancelAdjustment(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        String cancelReason = body != null ? body.get("cancelReason") : null;
        StockAdjustmentResponse response = stockAdjustmentService.cancelAdjustment(id, cancelReason, actor);
        return ResponseEntity.ok(response);
    }

    /**
     * Xem chi tiết phiếu điều chỉnh tồn kho.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'WAREHOUSE', 'ACCOUNTANT', 'ADMIN')")
    public ResponseEntity<StockAdjustmentResponse> getAdjustmentDetail(@PathVariable Long id) {
        StockAdjustmentResponse response = stockAdjustmentService.getAdjustmentDetail(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Tìm kiếm và phân trang danh sách phiếu điều chỉnh tồn kho.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'WAREHOUSE', 'ACCOUNTANT', 'ADMIN')")
    public ResponseEntity<PageResponse<StockAdjustmentResponse>> searchAdjustments(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<StockAdjustmentResponse> response = stockAdjustmentService.searchAdjustments(
                warehouseId, status, reason, fromDate, toDate, keyword, page, size);
        return ResponseEntity.ok(response);
    }
}
