package com.erp.backend.dto.debt;

/** S7-06: Đại lý xác nhận (AGENCY_ACCEPTED) hoặc phản hồi lệch (AGENCY_DISPUTED, bắt buộc feedback). */
public record DebtReconciliationRespondRequest(
        String status,
        String feedback,
        String signerName) {
}
