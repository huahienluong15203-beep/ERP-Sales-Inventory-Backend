package com.erp.backend.controller;

import com.erp.backend.dto.debt.OverdueReportResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.OverdueDebtReportService;
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
 * S8-09: Báo cáo công nợ quá hạn.
 * - GET /api/reports/overdue-debts?salesRepId=&top=10: theo nhóm tuổi nợ, theo NV phụ trách, đại lý rủi ro cao nhất
 * - GET /api/reports/overdue-debts/export?salesRepId=: tệp Excel (3 sheet)
 */
@RestController
@RequestMapping("/api/reports/overdue-debts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN', 'SALES_MANAGER', 'SALES_REP')")
public class OverdueDebtReportController {

    private final OverdueDebtReportService overdueDebtReportService;

    @GetMapping
    public OverdueReportResponse report(@RequestParam(required = false) Long salesRepId,
                                        @RequestParam(required = false) Integer top,
                                        @AuthenticationPrincipal UserDetailsImpl actor) {
        return overdueDebtReportService.report(salesRepId, top, actor);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Long salesRepId,
                                         @AuthenticationPrincipal UserDetailsImpl actor) {
        OverdueDebtReportService.ExportFile file = overdueDebtReportService.export(salesRepId, actor);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file.content());
    }
}
