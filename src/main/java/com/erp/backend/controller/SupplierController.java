package com.erp.backend.controller;

import com.erp.backend.dto.supplier.ChangeSupplierStatusRequest;
import com.erp.backend.dto.supplier.CreateSupplierRequest;
import com.erp.backend.dto.supplier.SupplierProfileRequest;
import com.erp.backend.dto.supplier.SupplierResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.service.SupplierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * S2-09: Danh mục nhà cung cấp.
 * - Xem: Admin, Quản lý kho, Nhân viên kho, Quản lý kinh doanh, Kế toán.
 * - Thêm/sửa/ngừng giao dịch/xoá: Admin, Quản lý kho, Nhân viên kho.
 */
@RestController
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER', 'WAREHOUSE', 'SALES_MANAGER', 'ACCOUNTANT')")
public class SupplierController {

    private final SupplierService supplierService;

    /** Vd: ?keyword=vinamilk&status=ACTIVE&page=0&size=20 */
    @GetMapping
    public PageResponse<SupplierResponse> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return supplierService.search(keyword, status, page, size);
    }

    @GetMapping("/{id}")
    public SupplierResponse getById(@PathVariable Long id) {
        return supplierService.getById(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER', 'WAREHOUSE')")
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody CreateSupplierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supplierService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER', 'WAREHOUSE')")
    public SupplierResponse update(@PathVariable Long id, @Valid @RequestBody SupplierProfileRequest request) {
        return supplierService.update(id, request);
    }

    /** Ngừng giao dịch (bắt buộc lý do) hoặc giao dịch lại. */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER', 'WAREHOUSE')")
    public SupplierResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ChangeSupplierStatusRequest request) {
        return supplierService.changeStatus(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER', 'WAREHOUSE')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        supplierService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
