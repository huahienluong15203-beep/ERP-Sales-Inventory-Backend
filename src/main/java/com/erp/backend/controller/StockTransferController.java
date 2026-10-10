package com.erp.backend.controller;

import com.erp.backend.dto.inventory.transfer.CreateStockTransferPayload;
import com.erp.backend.dto.inventory.transfer.ReceiveTransferPayload;
import com.erp.backend.dto.inventory.transfer.StockTransferResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.StockTransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * S5-07 / SCRUM-163: API Quản lý Phiếu Chuyển Kho Nội Bộ.
 * Dành cho Nhân viên kho, Quản lý kho, Kế toán và Admin.
 */
@RestController
@RequestMapping("/api/inventory/transfers")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT')")
public class StockTransferController {

    private final StockTransferService stockTransferService;

    /**
     * Danh sách phiếu chuyển kho có bộ lọc theo trạng thái, kho đi, kho đến, từ khóa.
     */
    @GetMapping
    public ResponseEntity<List<StockTransferResponse>> searchTransfers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceWarehouse,
            @RequestParam(required = false) String destWarehouse,
            @RequestParam(required = false) String keyword
    ) {
        List<StockTransferResponse> transfers = stockTransferService.searchTransfers(status, sourceWarehouse, destWarehouse, keyword);
        return ResponseEntity.ok(transfers);
    }

    /**
     * Xem chi tiết một phiếu chuyển kho.
     */
    @GetMapping("/{id}")
    public ResponseEntity<StockTransferResponse> getTransferById(@PathVariable Long id) {
        StockTransferResponse response = stockTransferService.getTransferById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Lập phiếu chuyển kho mới (AC1).
     */
    @PostMapping
    public ResponseEntity<StockTransferResponse> createTransfer(
            @Valid @RequestBody CreateStockTransferPayload payload,
            @AuthenticationPrincipal UserDetailsImpl actor
    ) {
        StockTransferResponse response = stockTransferService.createTransfer(payload, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Kho đi xuất kho: chuyển sang trạng thái IN_TRANSIT (AC2).
     * Trừ kho nguồn, ghi nhận hàng Đang trên đường, CHƯA cộng vào kho đến.
     */
    @PutMapping("/{id}/dispatch")
    public ResponseEntity<StockTransferResponse> dispatchTransfer(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl actor
    ) {
        StockTransferResponse response = stockTransferService.dispatchTransfer(id, actor);
        return ResponseEntity.ok(response);
    }

    /**
     * Kho đến xác nhận nhận hàng (AC3, AC4).
     * - AC3: Nhận đủ -> tồn kho đến được cộng (COMPLETED).
     * - AC4: Chênh lệch -> bắt buộc nhập lý do (DISCREPANCY_RESOLVED).
     */
    @PutMapping("/{id}/receive")
    public ResponseEntity<StockTransferResponse> receiveTransfer(
            @PathVariable Long id,
            @Valid @RequestBody ReceiveTransferPayload payload,
            @AuthenticationPrincipal UserDetailsImpl actor
    ) {
        StockTransferResponse response = stockTransferService.receiveTransfer(id, payload, actor);
        return ResponseEntity.ok(response);
    }
}
