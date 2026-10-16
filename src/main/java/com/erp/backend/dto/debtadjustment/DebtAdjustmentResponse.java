package com.erp.backend.dto.debtadjustment;

import java.math.BigDecimal;
import java.util.List;

/**
 * S8-02: Chứng từ điều chỉnh giảm trừ công nợ theo phiếu trả hàng (tên trường khớp FE).
 * id = id phiếu trả hàng. status: PENDING_APPROVAL (chờ kế toán duyệt) / APPROVED (đã lập chứng từ, đã giảm công nợ)
 * / REJECTED (kế toán từ chối). adjustmentCode chỉ có khi đã duyệt (số GG2026-000001).
 */
public record DebtAdjustmentResponse(
        Long id,
        String adjustmentCode,
        String returnOrderCode,
        Long originalInvoiceId,
        String originalInvoiceCode,
        String originalInvoiceDate,
        Long customerId,
        String customerCode,
        String customerName,
        String customerPhone,
        String customerAddress,
        String status,
        List<DebtAdjustmentItemResponse> items,
        BigDecimal totalAdjustmentAmount,
        String returnReason,
        String notes,
        String createdAt,
        String createdBy,
        String approvedAt,
        String approvedBy,
        String approvalNote,
        Long creditNoteId) {
}
