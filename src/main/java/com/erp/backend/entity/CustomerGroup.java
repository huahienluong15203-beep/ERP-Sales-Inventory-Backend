package com.erp.backend.entity;

/**
 * S3-03: Nhóm khách hàng của đại lý.
 * Nhóm khách hàng quyết định bảng giá được áp dụng (dùng chung với S2-10 Bảng giá).
 */
public enum CustomerGroup {
    DEALER_LEVEL_1("Đại lý cấp 1"),
    DEALER_LEVEL_2("Đại lý cấp 2"),
    RETAIL("Khách lẻ");

    private final String label;

    CustomerGroup(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
