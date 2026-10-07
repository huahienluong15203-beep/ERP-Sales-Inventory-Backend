package com.erp.backend.dto.supplier;

import java.time.LocalDateTime;

/** S2-09: Nhà cung cấp trả về cho Frontend. */
public record SupplierResponse(
        Long id,
        String code,
        String name,
        String taxCode,
        String contactName,
        String phone,
        String email,
        String address,
        String paymentTerms,
        String note,
        String status,
        String statusReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
