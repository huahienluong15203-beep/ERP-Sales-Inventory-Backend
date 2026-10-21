package com.erp.backend.controller;

import com.erp.backend.dto.inventory.aging.*;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.InventoryAgingReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * S8-07: API Báo cáo tồn kho và vòng quay hàng hóa (Aging Report).
 * - Xem giá trị tồn theo kho và theo nhóm hàng.
 * - Xem số ngày tồn bình quân của từng SKU, chỉ ra hàng chậm luân chuyển.
 * - Xuất file Excel báo cáo.
 */
@Tag(name = "Inventory Aging Report", description = "Báo cáo tồn kho và vòng quay hàng hóa, cảnh báo đọng vốn")
@RestController
@RequestMapping("/api/inventory/aging-report")
@RequiredArgsConstructor
public class InventoryAgingReportController {

    private final InventoryAgingReportService agingReportService;

    /**
     * Lấy báo cáo tuổi hàng (Aging) và vòng quay tồn kho.
     */
    @Operation(summary = "Lấy báo cáo tồn kho và vòng quay hàng hóa theo bộ lọc")
    @GetMapping
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'WAREHOUSE', 'ADMIN', 'SALES_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<InventoryAgingReportResponse> getAgingReport(
            @RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String agingBracket,
            @RequestParam(required = false) String keyword,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(agingReportService.getAgingReport(warehouseId, categoryId, agingBracket, keyword, actor));
    }

    /**
     * Xuất file Excel báo cáo luân chuyển kho (.xlsx).
     */
    @Operation(summary = "Xuất file Excel báo cáo luân chuyển kho")
    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'WAREHOUSE', 'ADMIN', 'SALES_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String agingBracket,
            @RequestParam(required = false) String keyword,
            @AuthenticationPrincipal UserDetailsImpl actor) throws IOException {

        byte[] excelBytes = agingReportService.exportToExcel(warehouseId, categoryId, agingBracket, keyword, actor);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "Bao_cao_luan_chuyen_kho_" + timestamp + ".xlsx";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }

    /**
     * Thống kê giá trị tồn kho theo từng kho lưu trữ.
     */
    @Operation(summary = "Thống kê giá trị tồn kho theo từng kho")
    @GetMapping("/summary-by-warehouse")
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'WAREHOUSE', 'ADMIN', 'SALES_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<InventoryAgingWarehouseValueResponse>> getSummaryByWarehouse(
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(agingReportService.getSummaryByWarehouse(actor));
    }

    /**
     * Thống kê giá trị tồn kho theo từng nhóm hàng (Category).
     */
    @Operation(summary = "Thống kê giá trị tồn kho theo từng nhóm hàng")
    @GetMapping("/summary-by-category")
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'WAREHOUSE', 'ADMIN', 'SALES_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<InventoryAgingCategoryValueResponse>> getSummaryByCategory(
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(agingReportService.getSummaryByCategory(actor));
    }
}
