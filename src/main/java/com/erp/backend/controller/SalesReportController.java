package com.erp.backend.controller;

import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.SalesReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;

/**
 * S8-06: Báo cáo doanh số đa chiều. QL kinh doanh và Admin xem được; giá vốn / biên lợi nhuận chỉ QL kinh doanh
 * (service trả DTO riêng theo quyền).
 */
@RestController
@RequestMapping("/api/reports/sales")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
public class SalesReportController {

    private final SalesReportService salesReportService;

    /**
     * Vd: ?groupBy=REGION&fromDate=2026-10-01&toDate=2026-10-31&compare=LAST_YEAR
     * groupBy: REP | REGION | CUSTOMER | CATEGORY | SKU (mặc định REP); compare: PREVIOUS (mặc định) | LAST_YEAR | NONE.
     */
    @GetMapping
    public ResponseEntity<Object> report(@RequestParam(required = false) String groupBy,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                         @RequestParam(required = false) String compare,
                                         @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(salesReportService.report(groupBy, fromDate, toDate, compare, actor));
    }

    /** Xuất Excel cùng bộ lọc. Tên tệp: BaoCaoDoanhSo_<nhóm>_<từ ngày>_<đến ngày>.xlsx */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String groupBy,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                         @RequestParam(required = false) String compare,
                                         @AuthenticationPrincipal UserDetailsImpl actor) {
        SalesReportService.ExportFile file = salesReportService.export(groupBy, fromDate, toDate, compare, actor);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file.content());
    }
}
