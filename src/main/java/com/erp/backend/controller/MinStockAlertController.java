package com.erp.backend.controller;

import com.erp.backend.dto.inventory.alert.MinStockAlertResponse;
import com.erp.backend.dto.inventory.alert.MinStockSummaryResponse;
import com.erp.backend.dto.inventory.alert.UpdateMinThresholdPayload;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.MinStockAlertService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.erp.backend.dto.user.PageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * S5-09: Controller API Cảnh báo tồn dưới mức tối thiểu & Khai báo định mức.
 * - AC1: Khai báo tồn tối thiểu theo SKU và theo kho.
 * - AC2: Hàng dưới ngưỡng hiển thị nổi bật trong sổ tồn và trên dashboard kho.
 * - Subtask SCRUM-164: API check SKU dưới định mức.
 */
@RestController
@RequestMapping("/api/inventory/min-stock-alerts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT', 'SALES_MANAGER')")
public class MinStockAlertController {

    private final MinStockAlertService minStockAlertService;

    /**
     * Lấy danh sách cấu hình định mức & trạng thái cảnh báo tồn kho (AC1 & AC2).
     * Hỗ trợ phân trang (?page=0&size=50) để load nhanh tức thì.
     */
    @GetMapping
    public ResponseEntity<?> fetchConfigs(
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) Boolean belowThresholdOnly,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        if (page != null || size != null) {
            int p = (page != null && page >= 0) ? page : 0;
            int s = (size != null && size > 0) ? size : 50;
            PageResponse<MinStockAlertResponse> pageRes = minStockAlertService.fetchMinStockConfigsPage(
                    warehouseCode, belowThresholdOnly, status, keyword, p, s, actor);
            return ResponseEntity.ok()
                    .header("X-Total-Count", String.valueOf(pageRes.totalElements()))
                    .header("X-Total-Pages", String.valueOf(pageRes.totalPages()))
                    .body(pageRes);
        }

        List<MinStockAlertResponse> list = minStockAlertService.fetchMinStockConfigs(
                warehouseCode, belowThresholdOnly, status, keyword, actor);
        if (limit != null && limit > 0) {
            list = list.stream().limit(limit).toList();
        }
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(list.size()))
                .body(list);
    }

    /**
     * Khai báo / Cập nhật định mức tồn tối thiểu cho SKU tại kho (AC1).
     */
    @PostMapping
    // Mục 12: Kế toán / QL kinh doanh chỉ xem cảnh báo, không sửa ngưỡng tồn tối thiểu
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN')")
    public MinStockAlertResponse updateThreshold(
            @Valid @RequestBody UpdateMinThresholdPayload payload,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return minStockAlertService.updateMinThreshold(payload, actor);
    }

    /**
     * Subtask SCRUM-164: BE: API check SKU dưới định mức.
     */
    @GetMapping("/check")
    public List<MinStockAlertResponse> checkBelowThreshold(
            @RequestParam(required = false) String warehouseCode,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return minStockAlertService.checkBelowThreshold(warehouseCode, actor);
    }

    /**
     * Thống kê cảnh báo tồn kho phục vụ widget trên Dashboard kho (AC2).
     */
    @GetMapping("/summary")
    public MinStockSummaryResponse getSummary(
            @RequestParam(required = false) String warehouseCode,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return minStockAlertService.getSummary(warehouseCode, actor);
    }
}
