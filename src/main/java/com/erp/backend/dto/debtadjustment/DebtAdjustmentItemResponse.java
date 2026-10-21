package com.erp.backend.dto.debtadjustment;

import java.math.BigDecimal;

/**
 * S8-02: Dòng hàng của chứng từ điều chỉnh (tên trường khớp màn hình FE "Chứng từ giảm trừ công nợ").
 * originalSoldPrice = giá đã bán trên hoá đơn gốc (thành tiền sau chiết khấu / SL hoá đơn);
 * currentCatalogPrice = giá bảng giá hiện hành của nhóm đại lý (chỉ để đối chiếu, null nếu SKU không còn trong bảng giá).
 */
public record DebtAdjustmentItemResponse(
        Long id,
        String productSku,
        String productName,
        String baseUnit,
        BigDecimal returnQuantity,
        BigDecimal originalSoldPrice,
        BigDecimal currentCatalogPrice,
        BigDecimal adjustmentAmount) {
}
