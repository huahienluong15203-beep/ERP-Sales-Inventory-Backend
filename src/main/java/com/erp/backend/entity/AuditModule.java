package com.erp.backend.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * S2-04: Phân hệ / loại nghiệp vụ cần ghi nhật ký hệ thống:
 * - INVENTORY: Tồn kho, điều chỉnh kiểm kê, nhập/xuất kho
 * - PRICING: Bảng giá, giá vốn, chính sách chiết khấu
 * - DEBT_LIMIT: Hạn mức công nợ, số ngày nợ, khóa/mở giao dịch đại lý
 * - INVOICE: Hóa đơn, thanh toán, công nợ
 * - CUSTOMER: Hồ sơ đại lý
 */
@Getter
public enum AuditModule {
    INVENTORY("Tồn kho"),
    PRICING("Bảng giá"),
    DEBT_LIMIT("Hạn mức công nợ"),
    INVOICE("Hóa đơn"),
    CUSTOMER("Đại lý");

    private final String label;

    AuditModule(String label) {
        this.label = label;
    }
}
