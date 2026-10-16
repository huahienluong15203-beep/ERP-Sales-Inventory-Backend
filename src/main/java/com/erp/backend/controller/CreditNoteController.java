package com.erp.backend.controller;

import com.erp.backend.dto.creditnote.CreditNoteCreateRequest;
import com.erp.backend.dto.creditnote.CreditNoteResponse;
import com.erp.backend.dto.creditnote.PendingCreditReturnResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.CreditNoteService;
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
 * S8-02: Chứng từ giảm trừ công nợ khi trả hàng.
 * - GET  /api/credit-notes/pending-returns: phiếu trả đã nhập kho chờ lập chứng từ (kế toán / quản lý)
 * - POST /api/credit-notes {salesReturnId, note}: lập chứng từ và giảm trừ công nợ ngay (kế toán / admin)
 * - GET  /api/credit-notes?customerId=&invoiceId=&fromDate=&toDate=: tra cứu
 * - GET  /api/credit-notes/{id}: chi tiết (dòng giảm trừ, hoá đơn được đối trừ)
 */
@RestController
@RequestMapping("/api/credit-notes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN', 'SALES_MANAGER', 'SALES_REP', 'CUSTOMER')")
public class CreditNoteController {

    private final CreditNoteService creditNoteService;

    @GetMapping("/pending-returns")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN', 'SALES_MANAGER')")
    public List<PendingCreditReturnResponse> pendingReturns(@AuthenticationPrincipal UserDetailsImpl actor) {
        return creditNoteService.pendingReturns(actor);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN')")
    public ResponseEntity<CreditNoteResponse> create(@Valid @RequestBody CreditNoteCreateRequest request,
                                                     @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(creditNoteService.createFromReturn(request, actor));
    }

    @GetMapping
    public PageResponse<CreditNoteResponse> search(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return creditNoteService.search(customerId, invoiceId, fromDate, toDate, page, size, actor);
    }

    @GetMapping("/{id}")
    public CreditNoteResponse get(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl actor) {
        return creditNoteService.get(id, actor);
    }
}
