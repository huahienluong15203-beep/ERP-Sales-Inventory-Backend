package com.erp.backend.controller;

import com.erp.backend.dto.inventory.lot.*;
import com.erp.backend.dto.product.ProductResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.ProductLotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Controller quản lý Lô hàng & Hạn sử dụng (SCRUM-48 / S6-01).
 * Tuân thủ quy chuẩn Backend Engineering Best Practices: Thin Controller, @Valid, Method Security.
 */
@RestController
@RequestMapping("/api/inventory/lots")
@RequiredArgsConstructor
@Tag(name = "Product Lot & Expiry Management", description = "API Quản lý Lô hàng, Hạn sử dụng, Tính tồn kho theo lô & Hàm kiểm tra HSD xuất kho (S6-01)")
public class ProductLotController {

    private final ProductLotService productLotService;

    // =========================================================================
    // 1. TÌM KIẾM, PHÂN TRANG & CHI TIẾT LÔ HÀNG
    // =========================================================================

    @GetMapping
    @Operation(summary = "Tra cứu danh sách lô hàng theo bộ lọc & phân trang",
            description = "Tra cứu lô theo kho, sản phẩm, từ khóa, trạng thái (ACTIVE/LOCKED) và tình trạng hạn dùng (SAFE, NEAR_EXPIRATION, EXPIRED, OUT_OF_STOCK).")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT', 'SALES_MANAGER')")
    public ResponseEntity<PageResponse<ProductLotResponse>> searchLots(
            @Parameter(description = "ID kho") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "ID sản phẩm") @RequestParam(required = false) Long productId,
            @Parameter(description = "Từ khóa tìm kiếm (số lô, SKU, tên SP, NCC)") @RequestParam(required = false) String keyword,
            @Parameter(description = "Trạng thái lô: ACTIVE, LOCKED") @RequestParam(required = false) String status,
            @Parameter(description = "Tình trạng hạn dùng: SAFE, NEAR_EXPIRATION, EXPIRED, OUT_OF_STOCK") @RequestParam(required = false) String expiryStatus,
            @Parameter(description = "Ngưỡng cảnh báo sắp hết hạn (ngày, mặc định 30)") @RequestParam(required = false, defaultValue = "30") Integer nearExpiryDays,
            @Parameter(description = "Số trang (bắt đầu từ 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Số bản ghi mỗi trang") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sắp xếp theo trường (vd: id,desc)") @RequestParam(defaultValue = "id,desc") String sort,
            @AuthenticationPrincipal UserDetailsImpl actor) {

        String[] sortParts = sort.split(",");
        String sortProp = sortParts[0];
        Sort.Direction direction = sortParts.length > 1 && "desc".equalsIgnoreCase(sortParts[1])
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(direction, sortProp));
        PageResponse<ProductLotResponse> response = productLotService.searchLots(
                warehouseId, productId, keyword, status, expiryStatus, nearExpiryDays, pageRequest, actor);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Xem chi tiết một lô hàng theo ID", description = "Lấy thông tin chi tiết lô hàng gồm NSX, HSD, số ngày còn hạn và trạng thái.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT', 'SALES_MANAGER')")
    public ResponseEntity<ProductLotResponse> getLotById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(productLotService.getLotById(id, actor));
    }

    // =========================================================================
    // 2. KHAI BÁO & CẬP NHẬT LÔ HÀNG
    // =========================================================================

    @PostMapping
    @Operation(summary = "Khai báo lô hàng mới", description = "Tạo mới một lô hàng với số lô, NSX, HSD và số lượng tồn ban đầu cho SKU tại kho.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN')")
    public ResponseEntity<ProductLotResponse> createLot(
            @Valid @RequestBody ProductLotCreateRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        ProductLotResponse response = productLotService.createLot(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật thông tin lô hàng", description = "Cập nhật ngày sản xuất, hạn sử dụng, ghi chú hoặc khóa/mở khóa lô hàng (ACTIVE/LOCKED).")
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'ADMIN')")
    public ResponseEntity<ProductLotResponse> updateLot(
            @PathVariable Long id,
            @Valid @RequestBody ProductLotUpdateRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(productLotService.updateLot(id, request, actor));
    }

    // =========================================================================
    // 3. BẬT / TẮT QUẢN LÝ LÔ THEO TỪNG SKU (S6-01)
    // =========================================================================

    @PatchMapping("/products/{productId}/batch-management")
    @Operation(summary = "Bật hoặc tắt quản lý lô theo từng SKU",
            description = "Cho phép Quản lý kho, Quản trị viên bật/tắt cơ chế theo dõi lô và HSD cho một mã SKU cụ thể.")
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'ADMIN', 'SALES_MANAGER')")
    public ResponseEntity<ProductResponse> toggleBatchManagement(
            @PathVariable Long productId,
            @Valid @RequestBody ProductBatchManagementUpdateRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(productLotService.toggleBatchManagement(productId, request.getHasBatchManagement(), actor));
    }

    // =========================================================================
    // 4. TÍNH TỒN KHO THEO LÔ (S6-01)
    // =========================================================================

    @GetMapping("/product-stock")
    @Operation(summary = "Tính tồn chi tiết theo lô của sản phẩm",
            description = "Tính tổng tồn thực tế, tồn còn hạn, tồn sắp hết hạn, tồn quá hạn và danh sách lô sắp xếp FEFO.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT', 'SALES_MANAGER', 'SALES_REP')")
    public ResponseEntity<ProductLotStockResponse> calculateStockByLot(
            @Parameter(description = "ID sản phẩm cần tính tồn", required = true) @RequestParam Long productId,
            @Parameter(description = "ID kho (để trống nếu muốn tính trên mọi kho được phân quyền)") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Ngưỡng cảnh báo sắp hết hạn (ngày, mặc định 30)") @RequestParam(required = false, defaultValue = "30") Integer nearExpiryDays,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(productLotService.calculateStockByLot(productId, warehouseId, nearExpiryDays, actor));
    }

    // =========================================================================
    // 5. HÀM KIỂM TRA HSD & XÁC THỰC XUẤT KHO THEO LÔ (S6-01)
    // =========================================================================

    @PostMapping("/validate-dispatch")
    @Operation(summary = "Kiểm tra HSD và tính hợp lệ khi xuất lô hàng",
            description = "Xác thực số lượng khả dụng, tình trạng hết hạn và trạng thái khóa của lô. Ngăn chặn xuất lô quá hạn trừ khi Quản lý kho xác nhận ghi đè kèm lý do.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN')")
    public ResponseEntity<LotDispatchValidationResponse> validateLotForDispatch(
            @Valid @RequestBody LotDispatchValidationRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(productLotService.validateLotForDispatch(request, actor));
    }

    // =========================================================================
    // 6. CẢNH BÁO LÔ SẮP HẾT HẠN & BÁO CÁO TỔNG QUAN (S6-01)
    // =========================================================================

    @GetMapping("/expiring")
    @Operation(summary = "Cảnh báo danh sách lô sắp hết hạn",
            description = "Lấy danh sách các lô hàng đang hoạt động có HSD nằm trong ngưỡng khai báo (mặc định 30 ngày) để kịp thời xử lý đẩy bán.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT', 'SALES_MANAGER')")
    public ResponseEntity<PageResponse<ProductLotResponse>> getExpiringLots(
            @Parameter(description = "Ngưỡng cảnh báo số ngày tới hạn (mặc định 30)") @RequestParam(required = false, defaultValue = "30") Integer daysThreshold,
            @Parameter(description = "ID kho lọc") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Số trang (bắt đầu từ 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Số bản ghi mỗi trang") @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "expiredDate"));
        return ResponseEntity.ok(productLotService.getExpiringLots(daysThreshold, warehouseId, pageRequest, actor));
    }

    @GetMapping("/summary")
    @Operation(summary = "Thống kê tổng quan tình trạng Lô hàng & HSD",
            description = "Thống kê tổng số lượng lô, tồn an toàn, tồn sắp hết hạn, tồn quá hạn và hết tồn.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT', 'SALES_MANAGER')")
    public ResponseEntity<LotStockSummaryResponse> getLotSummary(
            @Parameter(description = "Ngưỡng cảnh báo số ngày tới hạn (mặc định 30)") @RequestParam(required = false, defaultValue = "30") Integer daysThreshold,
            @Parameter(description = "ID kho lọc") @RequestParam(required = false) Long warehouseId,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(productLotService.getLotSummary(daysThreshold, warehouseId, actor));
    }

    // =========================================================================
    // 7. GỢI Ý LÔ XUẤT THEO NGUYÊN TẮC FEFO & XÁC THỰC CHỌN LÔ (S6-04)
    // =========================================================================

    @GetMapping("/fefo-suggestion")
    @Operation(summary = "Gợi ý lô xuất theo nguyên tắc FEFO (S6-04)",
            description = "Mặc định gợi ý lô có hạn sử dụng gần nhất còn đủ số lượng. Trả về danh sách lô khả dụng cho component dropdown trên UI xuất kho.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT', 'SALES_MANAGER', 'SALES_REP')")
    public ResponseEntity<FefoLotSuggestionResponse> getFefoLotSuggestion(
            @Parameter(description = "ID sản phẩm", required = true) @RequestParam Long productId,
            @Parameter(description = "ID kho", required = true) @RequestParam Long warehouseId,
            @Parameter(description = "Số lượng yêu cầu xuất (theo ĐVT cơ sở)") @RequestParam(required = false, defaultValue = "1") BigDecimal requestedQuantity,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(productLotService.getFefoLotSuggestion(productId, warehouseId, requestedQuantity, actor));
    }

    @PostMapping("/validate-selection")
    @Operation(summary = "Kiểm tra tính hợp lệ khi thủ kho chọn lô xuất kho (S6-04)",
            description = "Xác thực tính hợp lệ của lô được chọn và bắt buộc nhập lý do nếu chọn khác lô mặc định do FEFO gợi ý.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN')")
    public ResponseEntity<LotSelectionValidationResponse> validateLotSelection(
            @Valid @RequestBody LotSelectionValidationRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(productLotService.validateLotSelection(request, actor));
    }
}
