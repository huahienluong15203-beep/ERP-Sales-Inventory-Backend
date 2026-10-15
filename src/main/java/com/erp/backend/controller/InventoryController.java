package com.erp.backend.controller;

import com.erp.backend.dto.inventory.StockLedgerRow;
import com.erp.backend.dto.inventory.StockLedgerSummary;
import com.erp.backend.dto.inventory.WarehouseOptionResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.StockLedgerExportService;
import com.erp.backend.service.StockLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final StockLedgerExportService stockLedgerExportService;

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

    /**
     * S8-08: Xuất Excel sổ tồn theo đúng bộ lọc đang xem (cùng tham số với danh sách, không phân trang).
     * Tên tệp: TonKho_<mã kho>_<yyyyMMdd>.xlsx (đọc từ header Content-Disposition).
     */
    @GetMapping("/api/inventory/stock-ledger/export")
    public ResponseEntity<byte[]> exportStockLedger(@RequestParam(required = false) Long warehouseId,
                                                    @RequestParam(required = false) Long categoryId,
                                                    @RequestParam(required = false) String stockStatus,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String sort,
                                                    @AuthenticationPrincipal UserDetailsImpl actor) {
        StockLedgerExportService.ExportFile file = stockLedgerExportService.export(warehouseId, categoryId, stockStatus,
                keyword, sort, actor);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName()).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file.content());
    }

    /** Danh sách kho cho ô chọn kho trên sổ tồn. Vd: ?status=ACTIVE */
    @GetMapping("/api/inventory/warehouses")
    public List<WarehouseOptionResponse> warehouses(@RequestParam(required = false) String status,
                                                    @AuthenticationPrincipal UserDetailsImpl actor) {
        return stockLedgerService.warehouses(status, actor);
    }
}
