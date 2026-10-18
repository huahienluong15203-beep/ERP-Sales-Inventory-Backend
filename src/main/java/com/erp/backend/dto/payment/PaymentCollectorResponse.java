package com.erp.backend.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Danh sách người thu tiền / kế toán lập phiếu đủ điều kiện theo phân quyền hệ thống.
 * Chỉ bao gồm Kế toán, Nhân viên kinh doanh, Quản lý kinh doanh và Admin.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCollectorResponse {
    private Long id;
    private String username;
    private String fullName;
    private String role;
    private String phone;
}
