package com.erp.backend.controller;

import com.erp.backend.dto.debt.DebtReconciliationCreateRequest;
import com.erp.backend.dto.debt.DebtReconciliationRespondRequest;
import com.erp.backend.dto.debt.DebtReconciliationResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.DebtReconciliationService;
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
 * S7-06: Đối chiếu công nợ định kỳ.
 * - GET  /api/debts/reconciliation?customerId=&status=ALL|DRAFT|CONFIRMED|AGENCY_ACCEPTED|AGENCY_DISPUTED
 * - GET  /api/debts/reconciliation/preview?customerId=&fromDate=&toDate=&statementDate=   (xem trước, chưa lưu)
 * - POST /api/debts/reconciliation  {customerId, fromDate, toDate, statementDate, notes, draft}
 * - GET  /api/debts/reconciliation/{id}
 * - POST /api/debts/reconciliation/{id}/confirm   (chốt bản nháp)
 * - POST /api/debts/reconciliation/{id}/respond   {status: AGENCY_ACCEPTED|AGENCY_DISPUTED, feedback, signerName} (đại lý)
 * - GET  /api/debts/reconciliation/{id}/print?autoprint=true   (trang in A4, "Lưu thành PDF")
 * Đại lý chỉ thấy biên bản của mình (không thấy bản nháp); NV kinh doanh chỉ đại lý mình phụ trách.
 */
@RestController
@RequestMapping("/api/debts/reconciliation")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN', 'SALES_MANAGER', 'SALES_REP', 'CUSTOMER')")
public class DebtReconciliationController {

    private final DebtReconciliationService reconciliationService;

    @GetMapping
    public List<DebtReconciliationResponse> list(@RequestParam(required = false) Long customerId,
                                                 @RequestParam(required = false) String status,
                                                 @AuthenticationPrincipal UserDetailsImpl actor) {
        return reconciliationService.list(customerId, status, actor);
    }

    @GetMapping("/preview")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN', 'SALES_MANAGER', 'SALES_REP')")
    public DebtReconciliationResponse preview(
            @RequestParam Long customerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate statementDate,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return reconciliationService.preview(customerId, fromDate, toDate, statementDate, actor);
    }

    @PostMapping
    public ResponseEntity<DebtReconciliationResponse> create(@RequestBody DebtReconciliationCreateRequest request,
                                                             @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reconciliationService.create(request, actor));
    }

    @GetMapping("/{id}")
    public DebtReconciliationResponse get(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl actor) {
        return reconciliationService.get(id, actor);
    }

    @PostMapping("/{id}/confirm")
    public DebtReconciliationResponse confirm(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl actor) {
        return reconciliationService.confirm(id, actor);
    }

    @PostMapping("/{id}/respond")
    public DebtReconciliationResponse respond(@PathVariable Long id,
                                              @RequestBody DebtReconciliationRespondRequest request,
                                              @AuthenticationPrincipal UserDetailsImpl actor) {
        return reconciliationService.respond(id, request, actor);
    }

    @GetMapping("/{id}/print")
    public ResponseEntity<String> print(@PathVariable Long id,
                                        @RequestParam(defaultValue = "false") boolean autoprint,
                                        @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(reconciliationService.printHtml(id, autoprint, actor));
    }
}
