package com.erp.backend.controller;

import com.erp.backend.dto.payment.CustomerInvoiceDebtResponse;
import com.erp.backend.dto.payment.PaymentReceiptCreateRequest;
import com.erp.backend.dto.payment.PaymentReceiptResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.PaymentReceiptPrintService;
import com.erp.backend.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * S7-02: API Ghi nhận thanh toán và cấn trừ công nợ đại lý.
 * - POST /api/payments: Ghi nhận thanh toán và cấn trừ công nợ (Kế toán, Quản trị, Nhân viên kinh doanh).
 * - GET /api/payments: Tra cứu danh sách phiếu thu.
 * - GET /api/payments/{id}: Xem chi tiết phiếu thu và phân bổ cấn trừ.
 * - GET /api/payments/{id}/print: In phiếu thu (S7-09).
 * - GET /api/payments/customers/{customerId}/invoices: Danh sách hoá đơn nợ để đối trừ.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN', 'SALES_MANAGER', 'SALES_REP', 'CUSTOMER')")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentReceiptPrintService paymentReceiptPrintService;

    /**
     * Ghi nhận thanh toán và cấn trừ vào công nợ.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN', 'SALES_REP')")
    public ResponseEntity<PaymentReceiptResponse> createReceipt(
            @Valid @RequestBody PaymentReceiptCreateRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        PaymentReceiptResponse response = paymentService.createReceipt(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Tra cứu danh sách phiếu thu có phân trang và bộ lọc.
     */
    @GetMapping
    public ResponseEntity<PageResponse<PaymentReceiptResponse>> searchReceipts(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        PageResponse<PaymentReceiptResponse> result = paymentService.searchReceipts(
                customerId, paymentMethod, status, fromDate, toDate, keyword, page, size, actor
        );
        return ResponseEntity.ok(result);
    }

    /**
     * Xem chi tiết 1 phiếu thu kèm danh sách phân bổ hoá đơn.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PaymentReceiptResponse> getReceipt(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        PaymentReceiptResponse response = paymentService.getReceipt(id, actor);
        return ResponseEntity.ok(response);
    }

    /**
     * S7-09: Mẫu in phiếu thu (HTML, 2 liên); in hoặc "Lưu dưới dạng PDF" từ trình duyệt.
     * ?autoprint=true tự mở hộp thoại in. Quyền như xem phiếu thu.
     */
    @GetMapping("/{id}/print")
    public ResponseEntity<String> printReceipt(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean autoprint,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(paymentReceiptPrintService.printHtml(id, autoprint, actor));
    }

    /**
     * Danh sách hoá đơn nợ của đại lý để đối trừ (hỗ trợ hiển thị trên modal ghi nhận thu tiền).
     */
    @GetMapping("/customers/{customerId}/invoices")
    public ResponseEntity<List<CustomerInvoiceDebtResponse>> getCustomerInvoicesForDebt(
            @PathVariable Long customerId,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        List<CustomerInvoiceDebtResponse> invoices = paymentService.getCustomerInvoicesForDebt(customerId, actor);
        return ResponseEntity.ok(invoices);
    }

    /**
     * S7-05: NVKD ghi nhận thu tiền tại tuyến kèm ảnh biên lai (trạng thái Chờ nộp).
     */
    @PostMapping("/route-collection")
    @PreAuthorize("hasAnyRole('SALES_REP', 'ADMIN')")
    public ResponseEntity<PaymentReceiptResponse> submitRouteCash(
            @Valid @RequestBody com.erp.backend.dto.payment.RouteCashCollectionRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        PaymentReceiptResponse response = paymentService.submitRouteCash(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * S7-05: Kế toán xác nhận đã nhận tiền nộp quỹ (cấn trừ hoá đơn nợ).
     */
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN')")
    public ResponseEntity<PaymentReceiptResponse> confirmRouteCash(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        PaymentReceiptResponse response = paymentService.confirmRouteCash(id, actor);
        return ResponseEntity.ok(response);
    }

    /**
     * S7-05: Kế toán từ chối nhận tiền nộp quỹ.
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN')")
    public ResponseEntity<PaymentReceiptResponse> rejectRouteCash(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) com.erp.backend.dto.payment.PaymentRejectRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        PaymentReceiptResponse response = paymentService.rejectRouteCash(id, request, actor);
        return ResponseEntity.ok(response);
    }

    /**
     * S7-05: Bảng đối chiếu tiền thu theo từng nhân viên và từng ngày.
     */
    @GetMapping("/reconciliation")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN', 'SALES_MANAGER', 'SALES_REP')")
    public ResponseEntity<com.erp.backend.dto.payment.PaymentReconciliationResponse> getReconciliation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long collectorId,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        com.erp.backend.dto.payment.PaymentReconciliationResponse response =
                paymentService.getReconciliation(fromDate, toDate, collectorId, actor);
        return ResponseEntity.ok(response);
    }
}
