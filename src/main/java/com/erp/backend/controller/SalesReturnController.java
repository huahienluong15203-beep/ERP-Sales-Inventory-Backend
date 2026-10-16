package com.erp.backend.controller;

import com.erp.backend.dto.salesreturn.ReturnableInvoiceLineResponse;
import com.erp.backend.dto.salesreturn.SalesReturnCreateRequest;
import com.erp.backend.dto.salesreturn.SalesReturnResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.SalesReturnService;
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
import java.util.Map;

/**
 * S8-01: REST API quản lý phiếu trả hàng từ đại lý và nhập lại kho (Sales Return Order).
 * - NVKD lập phiếu: chọn hoá đơn gốc, chọn dòng hàng, số lượng và lý do trả.
 * - Quản lý kho xác nhận nhận hàng: cộng tồn kho ngược và ghi thẻ kho.
 */
@RestController
@RequestMapping("/api/sales-returns")
@RequiredArgsConstructor
public class SalesReturnController {

    private final SalesReturnService salesReturnService;

    /**
     * AC1: Lập phiếu trả hàng từ đại lý.
     * NVKD, QLKD, Admin.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('SALES_REP', 'SALES_MANAGER', 'ADMIN')")
    public ResponseEntity<SalesReturnResponse> createSalesReturn(
            @Valid @RequestBody SalesReturnCreateRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        SalesReturnResponse response = salesReturnService.createSalesReturn(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * AC4: Quản lý kho xác nhận nhận hàng và nhập kho ngược.
     * Quản lý kho, Thủ kho, Admin.
     */
    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'WAREHOUSE', 'ADMIN')")
    public ResponseEntity<SalesReturnResponse> receiveAndRestock(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        String receiveNotes = body != null ? body.get("notes") : null;
        SalesReturnResponse response = salesReturnService.receiveAndRestock(id, receiveNotes, actor);
        return ResponseEntity.ok(response);
    }

    /**
     * Huỷ phiếu trả hàng (khi còn ở trạng thái PENDING_RECEIPT).
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SALES_REP', 'SALES_MANAGER', 'ADMIN')")
    public ResponseEntity<SalesReturnResponse> cancelSalesReturn(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        String cancelReason = body != null ? body.get("cancelReason") : null;
        SalesReturnResponse response = salesReturnService.cancelSalesReturn(id, cancelReason, actor);
        return ResponseEntity.ok(response);
    }

    /**
     * Lấy danh sách dòng hàng có thể trả của một hoá đơn gốc.
     */
    @GetMapping("/invoice-lines/{invoiceId}")
    @PreAuthorize("hasAnyRole('SALES_REP', 'SALES_MANAGER', 'ACCOUNTANT', 'ADMIN', 'WH_MANAGER', 'WAREHOUSE')")
    public ResponseEntity<List<ReturnableInvoiceLineResponse>> getReturnableInvoiceLines(
            @PathVariable Long invoiceId) {
        List<ReturnableInvoiceLineResponse> lines = salesReturnService.getReturnableInvoiceLines(invoiceId);
        return ResponseEntity.ok(lines);
    }

    /**
     * Chi tiết phiếu trả hàng.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SALES_REP', 'SALES_MANAGER', 'ACCOUNTANT', 'ADMIN', 'WH_MANAGER', 'WAREHOUSE')")
    public ResponseEntity<SalesReturnResponse> getSalesReturnDetail(@PathVariable Long id) {
        SalesReturnResponse response = salesReturnService.getSalesReturnDetail(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Tìm kiếm và phân trang phiếu trả hàng.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SALES_REP', 'SALES_MANAGER', 'ACCOUNTANT', 'ADMIN', 'WH_MANAGER', 'WAREHOUSE')")
    public ResponseEntity<PageResponse<SalesReturnResponse>> searchSalesReturns(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<SalesReturnResponse> response = salesReturnService.searchSalesReturns(
                customerId, warehouseId, status, fromDate, toDate, keyword, page, size);
        return ResponseEntity.ok(response);
    }
}
