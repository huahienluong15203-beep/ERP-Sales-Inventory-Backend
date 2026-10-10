package com.erp.backend.controller;

import com.erp.backend.dto.customer.CustomerResponse;
import com.erp.backend.dto.warehouse.*;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.WarehouseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * S5-03 / SCRUM-161: Controller quản lý đa kho và vị trí lưu trong kho (Khu / Kệ / Ô).
 * - Quyền đọc: ADMIN, WH_MANAGER, WAREHOUSE, SALES_MANAGER, ACCOUNTANT
 * - Quyền tạo/sửa/xóa kho & vị trí: ADMIN, WH_MANAGER (Quản lý kho)
 */
@RestController
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    // ==============================================================
    // 1. KHO HÀNG (WAREHOUSE CRUD)
    // ==============================================================

    @GetMapping("/api/warehouses")
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER', 'WAREHOUSE', 'SALES_MANAGER', 'ACCOUNTANT')")
    public List<WarehouseResponse> getAllWarehouses(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String keyword,
                                                   @AuthenticationPrincipal UserDetailsImpl actor) {
        return warehouseService.getAll(status, keyword, actor);
    }

    @GetMapping("/api/warehouses/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER', 'WAREHOUSE', 'SALES_MANAGER', 'ACCOUNTANT')")
    public WarehouseResponse getWarehouseById(@PathVariable Long id,
                                             @AuthenticationPrincipal UserDetailsImpl actor) {
        return warehouseService.getById(id, actor);
    }

    @PostMapping("/api/warehouses")
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER')")
    public ResponseEntity<WarehouseResponse> createWarehouse(@Valid @RequestBody WarehouseCreateRequest request,
                                                             @AuthenticationPrincipal UserDetailsImpl actor) {
        WarehouseResponse response = warehouseService.create(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/api/warehouses/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER')")
    public WarehouseResponse updateWarehouse(@PathVariable Long id,
                                             @Valid @RequestBody WarehouseUpdateRequest request,
                                             @AuthenticationPrincipal UserDetailsImpl actor) {
        return warehouseService.update(id, request, actor);
    }

    @DeleteMapping("/api/warehouses/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER')")
    public ResponseEntity<Void> deleteWarehouse(@PathVariable Long id,
                                                @AuthenticationPrincipal UserDetailsImpl actor) {
        warehouseService.delete(id, actor);
        return ResponseEntity.noContent().build();
    }

    // ==============================================================
    // 2. VỊ TRÍ LƯU TRONG KHO (WAREHOUSE LOCATIONS CRUD)
    // ==============================================================

    @GetMapping("/api/warehouses/{warehouseId}/locations")
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER', 'WAREHOUSE', 'SALES_MANAGER', 'ACCOUNTANT')")
    public List<WarehouseLocationResponse> getLocations(@PathVariable Long warehouseId,
                                                        @RequestParam(required = false) String locationType,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String keyword,
                                                        @AuthenticationPrincipal UserDetailsImpl actor) {
        return warehouseService.getLocations(warehouseId, locationType, status, keyword, actor);
    }

    @GetMapping("/api/warehouses/{warehouseId}/locations/{locationId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER', 'WAREHOUSE', 'SALES_MANAGER', 'ACCOUNTANT')")
    public WarehouseLocationResponse getLocationById(@PathVariable Long warehouseId,
                                                     @PathVariable Long locationId,
                                                     @AuthenticationPrincipal UserDetailsImpl actor) {
        return warehouseService.getLocationById(warehouseId, locationId, actor);
    }

    @PostMapping("/api/warehouses/{warehouseId}/locations")
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER')")
    public ResponseEntity<WarehouseLocationResponse> createLocation(@PathVariable Long warehouseId,
                                                                    @Valid @RequestBody WarehouseLocationCreateRequest request,
                                                                    @AuthenticationPrincipal UserDetailsImpl actor) {
        WarehouseLocationResponse response = warehouseService.createLocation(warehouseId, request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/api/warehouses/{warehouseId}/locations/{locationId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER')")
    public WarehouseLocationResponse updateLocation(@PathVariable Long warehouseId,
                                                    @PathVariable Long locationId,
                                                    @Valid @RequestBody WarehouseLocationUpdateRequest request,
                                                    @AuthenticationPrincipal UserDetailsImpl actor) {
        return warehouseService.updateLocation(warehouseId, locationId, request, actor);
    }

    @DeleteMapping("/api/warehouses/{warehouseId}/locations/{locationId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WH_MANAGER')")
    public ResponseEntity<Void> deleteLocation(@PathVariable Long warehouseId,
                                               @PathVariable Long locationId,
                                               @AuthenticationPrincipal UserDetailsImpl actor) {
        warehouseService.deleteLocation(warehouseId, locationId, actor);
        return ResponseEntity.noContent().build();
    }

    // ==============================================================
    // 3. GÁN KHO PHỤC VỤ MẶC ĐỊNH CHO ĐẠI LÝ
    // ==============================================================

    @PatchMapping("/api/customers/{id}/default-warehouse")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'ACCOUNTANT', 'WH_MANAGER')")
    public CustomerResponse assignDefaultWarehouse(@PathVariable Long id,
                                                  @RequestBody AssignDefaultWarehouseRequest request,
                                                  @AuthenticationPrincipal UserDetailsImpl actor) {
        Long warehouseId = request != null ? request.getWarehouseId() : null;
        return warehouseService.assignDefaultWarehouse(id, warehouseId, actor);
    }
}
