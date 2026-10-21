package com.erp.backend.controller;

import com.erp.backend.dto.payment.CustomerInvoiceDebtResponse;
import com.erp.backend.dto.payment.PaymentReceiptResponse;
import com.erp.backend.dto.portal.PortalDebtSummaryResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.PortalDebtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S7-07: Đại lý tự xem công nợ (chỉ tài khoản đại lý, chỉ dữ liệu của chính mình).
 * - GET /api/portal/debt: tổng còn nợ, hạn mức, hạn mức còn lại, nợ quá hạn, hạn trả gần nhất.
 * - GET /api/portal/debt/invoices?status=OPEN|OVERDUE|PAID|ALL: hoá đơn kèm hạn thanh toán, trạng thái.
 *   Tải lại hoá đơn: GET /api/invoices/{id}/print (đã kiểm đúng đại lý).
 * - GET /api/portal/debt/payments: phiếu thu của đại lý; in lại: GET /api/payments/{id}/print.
 */
@RestController
@RequestMapping("/api/portal/debt")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
public class PortalDebtController {

    private final PortalDebtService portalDebtService;

    @GetMapping
    public PortalDebtSummaryResponse summary(@AuthenticationPrincipal UserDetailsImpl actor) {
        return portalDebtService.summary(actor);
    }

    @GetMapping("/invoices")
    public PageResponse<CustomerInvoiceDebtResponse> invoices(@RequestParam(required = false) String status,
                                                              @RequestParam(required = false) String keyword,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "10") int size,
                                                              @AuthenticationPrincipal UserDetailsImpl actor) {
        return portalDebtService.invoices(status, keyword, page, size, actor);
    }

    @GetMapping("/payments")
    public PageResponse<PaymentReceiptResponse> payments(@RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size,
                                                         @AuthenticationPrincipal UserDetailsImpl actor) {
        return portalDebtService.payments(page, size, actor);
    }
}
