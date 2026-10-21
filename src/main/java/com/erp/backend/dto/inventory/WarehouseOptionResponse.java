package com.erp.backend.dto.inventory;

/** S5-05: Kho cho ô chọn kho trên sổ tồn. */
public record WarehouseOptionResponse(Long id, String code, String name, String address, String status) {
}
