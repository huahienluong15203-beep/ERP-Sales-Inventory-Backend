package com.erp.backend.controller;

import com.erp.backend.dto.inventory.receipt.GoodsReceiptCreateRequest;
import com.erp.backend.dto.inventory.receipt.GoodsReceiptResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.GoodsReceiptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * S5-04 / SCRUM-162: API Quản lý Phiếu Nhập Kho từ nhà cung cấp.
 * Dành cho Nhân viên kho, Quản lý kho, Kế toán và Admin.
 */
@RestController
@RequestMapping("/api/inventory/receipts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT')")
public class GoodsReceiptController {

    private final GoodsReceiptService goodsReceiptService;

    /**
     * Tìm kiếm và lọc danh sách phiếu nhập kho.
     */
    @GetMapping
    public ResponseEntity<List<GoodsReceiptResponse>> searchReceipts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        List<GoodsReceiptResponse> receipts = goodsReceiptService.searchReceipts(keyword, warehouseCode, status, fromDate, toDate);
        return ResponseEntity.ok(receipts);
    }

    /**
     * Xem chi tiết một phiếu nhập kho.
     */
    @GetMapping("/{id}")
    public ResponseEntity<GoodsReceiptResponse> getReceiptById(@PathVariable Long id) {
        GoodsReceiptResponse response = goodsReceiptService.getReceiptById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Lập phiếu nhập kho mới (Lưu nháp hoặc Xác nhận).
     */
    @PostMapping
    public ResponseEntity<GoodsReceiptResponse> createReceipt(
            @Valid @RequestBody GoodsReceiptCreateRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor
    ) {
        GoodsReceiptResponse response = goodsReceiptService.createReceipt(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Xác nhận phiếu nhập kho (AC4: Cộng tồn kho khi xác nhận).
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<GoodsReceiptResponse> confirmReceipt(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl actor
    ) {
        GoodsReceiptResponse response = goodsReceiptService.confirmReceipt(id, actor);
        return ResponseEntity.ok(response);
    }
}
