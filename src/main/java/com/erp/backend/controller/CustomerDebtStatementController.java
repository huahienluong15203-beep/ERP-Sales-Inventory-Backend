package com.erp.backend.controller;

import com.erp.backend.dto.debt.CustomerDebtStatementResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.CustomerDebtStatementService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * S8-10: Sổ chi tiết công nợ của 1 đại lý.
 * - GET /api/debts/customers/{customerId}/statement?fromDate=2026-09-01&toDate=2026-09-30
 *   (thiếu toDate = hôm nay, thiếu fromDate = đầu tháng của toDate)
 * - GET /api/debts/customers/{customerId}/statement/export?format=xlsx|pdf&fromDate=&toDate=&autoprint=true
 *   xlsx -> tải Excel; pdf -> trang in khổ A4 (trình duyệt "Lưu thành PDF")
 * NV kinh doanh chỉ xem đại lý mình phụ trách; đại lý chỉ xem chính mình (khác -> 404).
 */
@RestController
@RequestMapping("/api/debts/customers/{customerId}/statement")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN', 'SALES_MANAGER', 'SALES_REP', 'CUSTOMER')")
public class CustomerDebtStatementController {

    private final CustomerDebtStatementService statementService;

    @GetMapping
    public CustomerDebtStatementResponse statement(
            @PathVariable Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return statementService.statement(customerId, fromDate, toDate, actor);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @PathVariable Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(defaultValue = "false") boolean autoprint,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        CustomerDebtStatementService.ExportFile file =
                statementService.export(customerId, fromDate, toDate, format, autoprint, actor);
        ContentDisposition cd = file.fileName().endsWith(".html")
                ? ContentDisposition.inline().filename(file.fileName(), StandardCharsets.UTF_8).build()
                : ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
