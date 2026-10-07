package com.erp.backend.controller;

import com.erp.backend.dto.category.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.service.ProductCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * S2-06: Nhóm hàng nhiều cấp.
 * - Xem: các vai trò được xem danh mục sản phẩm.
 * - Thêm/sửa/xoá nhóm, chuyển sản phẩm: Admin, Quản lý kinh doanh.
 */
@RestController
@RequestMapping("/api/product-categories")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WH_MANAGER', 'WAREHOUSE', 'SALES_REP', 'ACCOUNTANT')")
public class ProductCategoryController {

    private final ProductCategoryService categoryService;

    /** Toàn bộ cây dạng danh sách phẳng (id, parentId, level), Frontend tự dựng cây. */
    @GetMapping
    public List<CategoryResponse> getAll() {
        return categoryService.getAll();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CreateCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public CategoryResponse update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request);
    }

    /** Không xoá được nếu còn nhóm con hoặc còn sản phẩm (409). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Vd: ?includeSubgroups=true&page=0&size=20 */
    @GetMapping("/{id}/products")
    public PageResponse<CategoryProductItem> getProducts(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean includeSubgroups,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return categoryService.getProducts(id, includeSubgroups, page, size);
    }

    /** Chuyển sản phẩm vào nhóm này. Body: {"productIds":[1,2,3]} */
    @PutMapping("/{id}/products")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public MoveProductsResponse moveProducts(@PathVariable Long id, @Valid @RequestBody MoveProductsRequest request) {
        return categoryService.moveProducts(id, request);
    }
}
