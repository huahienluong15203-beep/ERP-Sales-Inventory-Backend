package com.erp.backend.controller;

import com.erp.backend.dto.inventory.StockLedgerRow;
import com.erp.backend.dto.inventory.StockLedgerSummary;
import com.erp.backend.dto.inventory.WarehouseOptionResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.StockLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * S5-05: Sổ tồn kho 3 cột (tồn thực tế / đang giữ chỗ / khả dụng) và danh sách kho.
 * Quyền: NV kho, QL kho, Admin, Kế toán, QL kinh doanh. NV kho chỉ xem kho mình được gắn.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT', 'SALES_MANAGER')")
public class InventoryController {

    private final StockLedgerService stockLedgerService;

    /** Vd: ?warehouseId=1&categoryId=3&stockStatus=IN_STOCK&keyword=coca&sort=physicalStock,desc&page=0&size=20 */
    @GetMapping("/api/inventory/stock-ledger")
    public PageResponse<StockLedgerRow> stockLedger(@RequestParam(required = false) Long warehouseId,
                                                    @RequestParam(required = false) Long categoryId,
                                                    @RequestParam(required = false) String stockStatus,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String sort,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @AuthenticationPrincipal UserDetailsImpl actor) {
        return stockLedgerService.search(warehouseId, categoryId, stockStatus, keyword, sort, page, size, actor);
    }

    /** Tổng số dòng, tổng 3 cột và số dòng theo trạng thái của toàn bộ kết quả đang lọc. */
    @GetMapping("/api/inventory/stock-ledger/summary")
    public StockLedgerSummary stockLedgerSummary(@RequestParam(required = false) Long warehouseId,
                                                 @RequestParam(required = false) Long categoryId,
                                                 @RequestParam(required = false) String stockStatus,
                                                 @RequestParam(required = false) String keyword,
                                                 @AuthenticationPrincipal UserDetailsImpl actor) {
        return stockLedgerService.summary(warehouseId, categoryId, stockStatus, keyword, actor);
    }

    /** Danh sách kho cho ô chọn kho trên sổ tồn. Vd: ?status=ACTIVE */
    @GetMapping("/api/inventory/warehouses")
    public List<WarehouseOptionResponse> warehouses(@RequestParam(required = false) String status,
                                                    @AuthenticationPrincipal UserDetailsImpl actor) {
        return stockLedgerService.warehouses(status, actor);
    }
}
