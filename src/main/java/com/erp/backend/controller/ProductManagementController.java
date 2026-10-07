package com.erp.backend.controller;

import com.erp.backend.dto.product.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.ProductExcelImportService;
import com.erp.backend.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Controller quản lý danh mục sản phẩm (S2-05, S2-07, S2-08).
 * Phân quyền:
 * - Tạo/Sửa/Xóa sản phẩm, Nhập Excel: ADMIN, SALES_MANAGER.
 * - Tra cứu/Xem danh mục sản phẩm: ADMIN, SALES_MANAGER, WH_MANAGER, WAREHOUSE, SALES_REP, ACCOUNTANT.
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "Product Management", description = "API Quản lý sản phẩm, đơn vị quy đổi & Nhập danh mục hàng loạt từ Excel (S2-05, S2-07, S2-08)")
public class ProductManagementController {

    private final ProductExcelImportService productExcelImportService;
    private final ProductService productService;

    // =========================================================================
    // S2-05 & S2-07: CRUD DANH MỤC SẢN PHẨM & KHAI BÁO SKU, ĐƠN VỊ TÍNH QUY ĐỔI
    // =========================================================================

    /**
     * S2-05 & S2-07: Khai báo mã SKU duy nhất, đơn vị tính cơ sở và nhiều đơn vị quy đổi (lon, lốc, thùng) kèm hệ số.
     */
    @PostMapping
    @Operation(summary = "Khai báo sản phẩm mới kèm đơn vị quy đổi",
            description = "Tạo mới sản phẩm với mã SKU duy nhất, đơn vị cơ sở và danh sách nhiều đơn vị quy đổi (lon, lốc, thùng) kèm hệ số (S2-05, S2-07).")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ResponseEntity<ProductDetailResponse> createProduct(
            @Valid @RequestBody CreateProductRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        ProductDetailResponse response = productService.createProduct(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * S2-05: Cập nhật thông tin sản phẩm.
     */
    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật thông tin sản phẩm", description = "Cập nhật tên, danh mục, quy cách, đơn vị cơ sở, giá vốn... (S2-05)")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ResponseEntity<ProductDetailResponse> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(productService.updateProduct(id, request, actor));
    }

    /**
     * S2-05 & S2-07: Lấy chi tiết sản phẩm theo ID kèm toàn bộ đơn vị quy đổi.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Lấy chi tiết sản phẩm theo ID", description = "Trả về thông tin chi tiết sản phẩm và danh sách đơn vị quy đổi (Thùng, Lốc...). Giá vốn chỉ trả về cho Quản lý kinh doanh.")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WH_MANAGER', 'WAREHOUSE', 'SALES_REP', 'ACCOUNTANT')")
    public ResponseEntity<ProductDetailResponse> getProductById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(productService.getProductById(id, actor));
    }

    /**
     * S2-05 & S2-07: Tra cứu sản phẩm theo mã SKU (nhân viên kho quét barcode hoặc tìm nhanh).
     */
    @GetMapping("/sku/{sku}")
    @Operation(summary = "Tra cứu sản phẩm theo mã SKU", description = "Tìm kiếm nhanh sản phẩm theo mã SKU duy nhất kèm danh sách đơn vị quy đổi. Giá vốn chỉ trả về cho Quản lý kinh doanh.")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WH_MANAGER', 'WAREHOUSE', 'SALES_REP', 'ACCOUNTANT')")
    public ResponseEntity<ProductDetailResponse> getProductBySku(
            @PathVariable String sku,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(productService.getProductBySku(sku, actor));
    }

    /**
     * S2-05: Tìm kiếm, lọc và phân trang danh mục sản phẩm.
     */
    @GetMapping
    @Operation(summary = "Danh sách sản phẩm (có tìm kiếm & phân trang)",
            description = "Tìm kiếm theo từ khóa (tên, mã SKU), lọc theo nhóm hàng hoặc trạng thái kinh doanh. Giá vốn chỉ trả về cho Quản lý kinh doanh.")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WH_MANAGER', 'WAREHOUSE', 'SALES_REP', 'ACCOUNTANT')")
    public ResponseEntity<PageResponse<ProductResponse>> searchProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id,desc") String sort,
            @AuthenticationPrincipal UserDetailsImpl actor) {

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 20 : Math.min(size, 100);

        Sort sortOrder = Sort.by(Sort.Direction.DESC, "id");
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw com.erp.backend.exception.BusinessException.badRequest("INVALID_SORT", "Tham số sắp xếp không hợp lệ (định dạng đúng: cot,chieu)");
            }
            String field = parts[0].trim();
            String dirStr = parts[1].trim().toLowerCase();
            if (!dirStr.equals("asc") && !dirStr.equals("desc")) {
                throw com.erp.backend.exception.BusinessException.badRequest("INVALID_SORT", "Chiều sắp xếp không hợp lệ (chỉ chấp nhận asc hoặc desc)");
            }

            java.util.Set<String> allowedFields = java.util.Set.of("id", "name", "sku", "category", "baseUnit", "packaging", "costPrice", "barcode", "status", "createdAt", "updatedAt");
            if (!allowedFields.contains(field)) {
                throw com.erp.backend.exception.BusinessException.badRequest("INVALID_SORT_FIELD", "Cột sắp xếp không hợp lệ: " + field);
            }

            if ("costPrice".equals(field)) {
                boolean canManageCost = actor != null && actor.getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_SALES_MANAGER") || a.getAuthority().equals("ROLE_ADMIN"));
                if (!canManageCost) {
                    // S205-01: Bỏ qua sắp xếp theo costPrice cho nhân viên không có quyền xem giá vốn, áp dụng mặc định id desc
                    field = "id";
                    dirStr = "desc";
                }
            }

            Sort.Direction direction = dirStr.equals("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
            sortOrder = Sort.by(direction, field);
        }

        PageResponse<ProductResponse> result = productService.searchProducts(
                keyword, category, status, PageRequest.of(safePage, safeSize, sortOrder), actor);
        return ResponseEntity.ok(result);
    }

    /**
     * S2-05: Ngừng kinh doanh hoặc xóa sản phẩm.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Ngừng kinh doanh sản phẩm", description = "Chuyển trạng thái sản phẩm sang INACTIVE để bảo vệ tính toàn vẹn dữ liệu lịch sử.")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ResponseEntity<Void> deleteProduct(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        productService.deleteProduct(id, actor);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // S2-08: NHẬP DANH MỤC SẢN PHẨM HÀNG LOẠT TỪ TỆP EXCEL (5.000 SKU)
    // =========================================================================

    /**
     * S2-08 AC1: Tải tệp mẫu Excel nhập danh mục sản phẩm (5.000 SKU).
     */
    @GetMapping("/import/template")
    @Operation(summary = "Tải tệp mẫu Excel sản phẩm", description = "Tải về tệp Excel mẫu chứa định dạng cột chuẩn và bảng hướng dẫn quy tắc nhập liệu.")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ResponseEntity<byte[]> downloadTemplate() {
        byte[] excelBytes = productExcelImportService.generateTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=Mau_Nhap_SanPham_ERP.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }

    /**
     * S2-08 AC1 & AC2: Xem trước dữ liệu và báo lỗi theo từng dòng trước khi nhập.
     * Đánh dấu rõ ràng dòng nào là CREATE (tạo mới) và dòng nào là UPDATE (cập nhật).
     */
    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Xem trước dữ liệu Excel sản phẩm", description = "Đọc tệp Excel, kiểm tra tính hợp lệ từng dòng, đánh dấu CREATE/UPDATE cho từng SKU và thống kê lỗi.")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ResponseEntity<ProductImportPreviewResponse> previewImport(
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(productExcelImportService.previewImport(file));
    }

    /**
     * S2-08: Thực thi nhập danh mục sản phẩm hàng loạt (hỗ trợ tới 5.000 SKU).
     * Dòng lỗi bị bỏ qua, dòng hợp lệ được tạo mới hoặc cập nhật, trả về báo cáo chi tiết.
     */
    @PostMapping(value = "/import/execute", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Thực thi nhập danh mục sản phẩm", description = "Nhập danh mục sản phẩm hàng loạt từ Excel. Dòng lỗi bỏ qua, SKU cũ cập nhật, SKU mới tạo mới.")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ResponseEntity<ProductImportSummaryResponse> executeImport(
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(productExcelImportService.executeImport(file));
    }
}
