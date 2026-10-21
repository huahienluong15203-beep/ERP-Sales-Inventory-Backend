package com.erp.backend.controller;

import com.erp.backend.dto.invoice.InvoiceIssueRequest;
import com.erp.backend.dto.invoice.InvoiceResponse;
import com.erp.backend.dto.invoice.InvoiceSummaryResponse;
import com.erp.backend.dto.invoice.UninvoicedOrderResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.InvoicePrintService;
import com.erp.backend.service.InvoiceService;
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
 * S7-01: Hoá đơn bán hàng.
 * - Lập / xem trước / chọn đơn chưa lập hoá đơn: Kế toán, Admin.
 * - Xem / in: Kế toán, Admin, QL kinh doanh, NV kinh doanh (đại lý mình phụ trách), Đại lý (hoá đơn của mình).
 * - Không có API sửa / xoá: hoá đơn đã phát hành chỉ lập chứng từ điều chỉnh.
 */
@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN', 'SALES_MANAGER', 'SALES_REP', 'CUSTOMER')")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoicePrintService invoicePrintService;

    /** Vd: ?customerId=6&fromDate=2026-10-01&toDate=2026-10-31&keyword=HD2026&page=0&size=20 */
    @GetMapping
    public PageResponse<InvoiceSummaryResponse> search(@RequestParam(required = false) Long customerId,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @AuthenticationPrincipal UserDetailsImpl actor) {
        return invoiceService.search(customerId, fromDate, toDate, keyword, page, size, actor);
    }

    /** Đơn đã giao chưa lập hoá đơn của đại lý trong kỳ (mặc định từ đầu tháng). Vd: ?customerId=6&fromDate=2026-10-01 */
    @GetMapping("/uninvoiced-orders")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN')")
    public List<UninvoicedOrderResponse> uninvoicedOrders(@RequestParam Long customerId,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                                          @AuthenticationPrincipal UserDetailsImpl actor) {
        return invoiceService.uninvoicedOrders(customerId, fromDate, toDate, actor);
    }

    /** Xem trước hoá đơn (không cấp số, không lưu). Body như lúc phát hành. */
    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN')")
    public InvoiceResponse preview(@Valid @RequestBody InvoiceIssueRequest request,
                                   @AuthenticationPrincipal UserDetailsImpl actor) {
        return invoiceService.preview(request, actor);
    }

    /**
     * Phát hành hoá đơn. Body: {"customerId":6,"orderIds":[101,105],"invoiceDate":"2026-10-15","note":"..."}
     * hoặc bỏ orderIds và gửi fromDate / toDate để gộp mọi đơn đã giao trong kỳ.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN')")
    public ResponseEntity<InvoiceResponse> issue(@Valid @RequestBody InvoiceIssueRequest request,
                                                 @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invoiceService.issue(request, actor));
    }

    @GetMapping("/{id}")
    public InvoiceResponse get(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl actor) {
        return invoiceService.get(id, actor);
    }

    /** Mẫu in hoá đơn (HTML A4); in hoặc "Lưu dưới dạng PDF" từ trình duyệt. ?autoprint=true tự mở hộp thoại in. */
    @GetMapping("/{id}/print")
    public ResponseEntity<String> print(@PathVariable Long id,
                                        @RequestParam(defaultValue = "false") boolean autoprint,
                                        @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(invoicePrintService.printHtml(id, autoprint, actor));
    }
}
