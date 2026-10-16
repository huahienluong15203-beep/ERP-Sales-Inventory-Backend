package com.erp.backend.controller;

import com.erp.backend.dto.debt.DebtLedgerResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.DebtLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * S7-03: Sổ công nợ & tuổi nợ theo đại lý.
 * - GET /api/debts/ledger?keyword=&salesRepId=&bucket=D0_30|D31_60|D61_90|D90_PLUS&overdueOnly=true
 *   &includeSettled=false&sort=OLDEST|OUTSTANDING|OVERDUE|CODE&page=0&size=50
 * - GET /api/debts/ledger/export (cùng bộ lọc, không phân trang) -> tệp Excel
 */
@RestController
@RequestMapping("/api/debts/ledger")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN', 'SALES_MANAGER', 'SALES_REP')")
public class DebtLedgerController {

    private final DebtLedgerService debtLedgerService;

    @GetMapping
    public DebtLedgerResponse ledger(@RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) Long salesRepId,
                                     @RequestParam(required = false) String bucket,
                                     @RequestParam(defaultValue = "false") boolean overdueOnly,
                                     @RequestParam(defaultValue = "false") boolean includeSettled,
                                     @RequestParam(required = false) String sort,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size,
                                     @AuthenticationPrincipal UserDetailsImpl actor) {
        return debtLedgerService.ledger(
                new DebtLedgerService.Filter(keyword, salesRepId, bucket, overdueOnly, includeSettled, sort),
                page, size, actor);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) Long salesRepId,
                                         @RequestParam(required = false) String bucket,
                                         @RequestParam(defaultValue = "false") boolean overdueOnly,
                                         @RequestParam(defaultValue = "false") boolean includeSettled,
                                         @RequestParam(required = false) String sort,
                                         @AuthenticationPrincipal UserDetailsImpl actor) {
        DebtLedgerService.ExportFile file = debtLedgerService.export(
                new DebtLedgerService.Filter(keyword, salesRepId, bucket, overdueOnly, includeSettled, sort), actor);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file.content());
    }
}
