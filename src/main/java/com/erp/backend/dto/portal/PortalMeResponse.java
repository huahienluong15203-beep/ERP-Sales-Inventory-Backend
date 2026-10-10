package com.erp.backend.dto.portal;

/** S4-10: Đại lý gắn với tài khoản cổng đại lý đang đăng nhập. */
public record PortalMeResponse(
        Long customerId,
        String customerCode,
        String customerName,
        String customerGroup,
        String customerGroupLabel,
        String salesRepName,
        String regionName,
        String phone,
        String address,
        boolean transactionLocked,
        String status) {
}
