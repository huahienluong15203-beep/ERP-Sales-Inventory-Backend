package com.erp.backend.controller;

import com.erp.backend.dto.dashboard.*;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.ExecutiveDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * S8-05: API Dashboard điều hành bán hàng và kho.
 * Phục vụ Quản lý kinh doanh nắm tình hình toàn công ty mỗi sáng trong hai phút.
 * - Thẻ chỉ số: doanh số hôm nay/tháng này, đơn chờ duyệt/chờ xuất, công nợ quá hạn, SKU dưới tồn tối thiểu.
 * - Biểu đồ: doanh số theo ngày trong tháng và theo khu vực.
 * - Danh sách đại lý quá hạn công nợ, xếp theo số tiền.
 * - Bộ đệm Cache tối ưu đảm bảo tải toàn bộ dữ liệu dưới 2 giây.
 */
@Tag(name = "Executive Dashboard", description = "Dashboard điều hành bán hàng và kho cho Quản lý kinh doanh")
@RestController
@RequestMapping("/api/dashboard/executive")
@RequiredArgsConstructor
public class ExecutiveDashboardController {

    private final ExecutiveDashboardService executiveDashboardService;

    /**
     * Thẻ chỉ số tổng quan bán hàng và tồn kho.
     */
    @Operation(summary = "Lấy các thẻ chỉ số điều hành tổng quan bán hàng & kho")
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN', 'WH_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<ExecutiveDashboardSummaryResponse> getSummary(
            @RequestParam(required = false, defaultValue = "false") boolean refresh,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(executiveDashboardService.getSummary(refresh, actor));
    }

    /**
     * Biểu đồ doanh số theo từng ngày trong tháng.
     */
    @Operation(summary = "Lấy dữ liệu biểu đồ doanh số theo ngày trong tháng")
    @GetMapping("/daily-sales")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN', 'WH_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<ExecutiveDailySalesResponse> getDailySales(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false, defaultValue = "false") boolean refresh,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(executiveDashboardService.getDailySalesInMonth(month, year, refresh, actor));
    }

    /**
     * Biểu đồ doanh số theo khu vực.
     */
    @Operation(summary = "Lấy dữ liệu biểu đồ doanh số theo khu vực")
    @GetMapping("/region-sales")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN', 'WH_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<ExecutiveRegionSalesResponse> getRegionSales(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false, defaultValue = "false") boolean refresh,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(executiveDashboardService.getSalesByRegion(month, year, refresh, actor));
    }

    /**
     * Danh sách đại lý quá hạn công nợ, xếp theo số tiền nợ giảm dần.
     */
    @Operation(summary = "Lấy danh sách đại lý quá hạn công nợ xếp theo số tiền nợ")
    @GetMapping("/overdue-agencies")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN', 'WH_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<ExecutiveOverdueAgenciesResponse> getOverdueAgencies(
            @RequestParam(required = false, defaultValue = "10") Integer limit,
            @RequestParam(required = false, defaultValue = "false") boolean refresh,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(executiveDashboardService.getOverdueAgencies(limit, refresh, actor));
    }

    /**
     * Lấy toàn bộ dữ liệu Dashboard điều hành trong một API duy nhất (tải xong < 2 giây).
     */
    @Operation(summary = "Lấy toàn bộ dữ liệu Dashboard điều hành (tổng hợp 1 lần, tải dưới 2 giây)")
    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN', 'WH_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<ExecutiveDashboardOverviewResponse> getCompleteDashboard(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false, defaultValue = "10") Integer limit,
            @RequestParam(required = false, defaultValue = "false") boolean refresh,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(executiveDashboardService.getCompleteDashboard(month, year, limit, refresh, actor));
    }

    /**
     * Kích hoạt làm mới bộ đệm (Cache) thủ công.
     */
    @Operation(summary = "Làm mới bộ nhớ đệm (Cache) Dashboard")
    @PostMapping("/refresh-cache")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN', 'WH_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<Map<String, Object>> refreshCache() {
        executiveDashboardService.evictDashboardCache();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Đã làm mới bộ nhớ đệm Dashboard điều hành thành công"
        ));
    }
}
