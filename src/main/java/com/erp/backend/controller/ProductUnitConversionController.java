package com.erp.backend.controller;

import com.erp.backend.dto.product.CreateProductUnitConversionRequest;
import com.erp.backend.dto.product.ProductUnitConversionResponse;
import com.erp.backend.dto.product.UnitConversionCalculateRequest;
import com.erp.backend.dto.product.UnitConversionResult;
import com.erp.backend.dto.product.UpdateProductUnitConversionRequest;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.ProductUnitConversionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Controller quản lý đơn vị tính quy đổi của sản phẩm (S2-07).
 * Phục vụ Nhân viên kho, Quản lý kho, Quản lý kinh doanh và Nhân viên kinh doanh:
 * - Khai báo đơn vị quy đổi (lon, lốc, thùng) kèm hệ số quy đổi về đơn vị cơ sở.
 * - Quy đổi về đơn vị cơ sở khi lập phiếu nhập/xuất kho hoặc đơn hàng.
 * - Đổi hệ số quy đổi không làm sai lệch các giao dịch đã ghi sổ trước đó.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Product Unit Conversions", description = "API Quản lý Đơn vị tính quy đổi của SKU (S2-07)")
public class ProductUnitConversionController {

    private final ProductUnitConversionService unitConversionService;

    /**
     * S2-07: Xem danh sách toàn bộ đơn vị tính của SKU (bao gồm đơn vị cơ sở hệ số 1).
     */
    @GetMapping("/api/products/{productId}/units")
    @Operation(summary = "Xem danh sách đơn vị tính của SKU",
            description = "Trả về danh sách toàn bộ đơn vị khả dụng của sản phẩm, gồm đơn vị cơ sở chuẩn (hệ số 1.0) và các đơn vị quy đổi.")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WH_MANAGER', 'WAREHOUSE', 'SALES_REP', 'ACCOUNTANT')")
    public ResponseEntity<List<ProductUnitConversionResponse>> getUnits(@PathVariable Long productId) {
        return ResponseEntity.ok(unitConversionService.getUnitConversions(productId));
    }

    /**
     * S2-07 AC1: Khai báo thêm đơn vị quy đổi mới cho SKU (vd: Thùng hệ số 24, Lốc hệ số 6...).
     */
    @PostMapping("/api/products/{productId}/units")
    @Operation(summary = "Khai báo thêm đơn vị quy đổi",
            description = "Nhân viên kho hoặc Quản lý khai báo thêm đơn vị mới (lon, lốc, thùng...) kèm hệ số quy đổi về đơn vị cơ sở.")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WH_MANAGER', 'WAREHOUSE')")
    public ResponseEntity<ProductUnitConversionResponse> addUnit(
            @PathVariable Long productId,
            @Valid @RequestBody CreateProductUnitConversionRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        ProductUnitConversionResponse response = unitConversionService.addUnitConversion(productId, request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * S2-07 AC3: Cập nhật hệ số quy đổi hoặc thông tin quy cách.
     * Lưu ý: Thay đổi ở đây ghi AuditLog và không làm sai lệch các giao dịch đã ghi trước đó.
     */
    @PutMapping("/api/products/{productId}/units/{conversionId}")
    @Operation(summary = "Cập nhật hệ số quy đổi",
            description = "Cập nhật hệ số hoặc mã vạch quy cách. Việc đổi hệ số không làm sai lệch các giao dịch kho đã ghi trước đó.")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WH_MANAGER', 'WAREHOUSE')")
    public ResponseEntity<ProductUnitConversionResponse> updateUnit(
            @PathVariable Long productId,
            @PathVariable Long conversionId,
            @Valid @RequestBody UpdateProductUnitConversionRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(unitConversionService.updateUnitConversion(productId, conversionId, request, actor));
    }

    /**
     * S2-07: Xoá đơn vị quy đổi của SKU.
     */
    @DeleteMapping("/api/products/{productId}/units/{conversionId}")
    @Operation(summary = "Xoá đơn vị quy đổi",
            description = "Xoá cấu hình đơn vị quy đổi của sản phẩm.")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WH_MANAGER', 'WAREHOUSE')")
    public ResponseEntity<Void> deleteUnit(
            @PathVariable Long productId,
            @PathVariable Long conversionId,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        unitConversionService.deleteUnitConversion(productId, conversionId, actor);
        return ResponseEntity.noContent().build();
    }

    /**
     * S2-07 AC2: Tiện ích tính toán quy đổi theo SKU hoặc productId.
     * Giúp nhập xuất theo thùng/lốc mà tự động tính ra số lượng đơn vị cơ sở ghi sổ kho.
     */
    @PostMapping("/api/products/convert")
    @Operation(summary = "Tiện ích quy đổi đơn vị tính",
            description = "Truyền vào SKU hoặc productId, đơn vị tính đã chọn và số lượng -> tính ra số lượng đơn vị cơ sở và snapshot ghi sổ.")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WH_MANAGER', 'WAREHOUSE', 'SALES_REP', 'ACCOUNTANT')")
    public ResponseEntity<UnitConversionResult> calculateConversion(
            @Valid @RequestBody UnitConversionCalculateRequest request) {
        return ResponseEntity.ok(unitConversionService.calculateConversion(request));
    }

    /**
     * S2-07 AC2: Quy đổi nhanh trên sản phẩm cụ thể qua path variable productId.
     */
    @PostMapping("/api/products/{productId}/units/convert")
    @Operation(summary = "Quy đổi nhanh cho sản phẩm theo ID",
            description = "Nhập số lượng theo đơn vị bất kỳ -> tính ra số lượng đơn vị cơ sở ghi sổ kho.")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WH_MANAGER', 'WAREHOUSE', 'SALES_REP', 'ACCOUNTANT')")
    public ResponseEntity<UnitConversionResult> calculateConversionByProductId(
            @PathVariable Long productId,
            @RequestParam String unitName,
            @RequestParam BigDecimal quantity) {
        UnitConversionCalculateRequest request = UnitConversionCalculateRequest.builder()
                .productId(productId)
                .unitName(unitName)
                .quantity(quantity)
                .build();
        return ResponseEntity.ok(unitConversionService.calculateConversion(request));
    }
}
