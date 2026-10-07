package com.erp.backend.dto.discount;

import java.math.BigDecimal;
import java.util.List;

/**
 * S3-01: Kết quả chiết khấu của một dòng hàng.
 * applied = chính sách được chọn (có lợi nhất cho khách), null nếu không có chính sách nào đạt bậc.
 * candidates = mọi chính sách đạt bậc, để giải thích vì sao chọn chính sách đó.
 */
public record DiscountCalculationResponse(
        Long productId,
        String productSku,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal grossAmount,
        BigDecimal discountAmount,
        BigDecimal netAmount,
        Candidate applied,
        List<Candidate> candidates) {

    public record Candidate(
            Long policyId,
            String policyCode,
            String policyName,
            String scope,
            String customerGroup,
            String discountType,
            BigDecimal tierMinQuantity,
            BigDecimal discountValue,
            BigDecimal discountPerUnit,
            BigDecimal discountAmount) {

        public Candidate(Long policyId, String policyCode, String policyName, String scope,
                         String discountType, BigDecimal tierMinQuantity, BigDecimal discountValue,
                         BigDecimal discountPerUnit, BigDecimal discountAmount) {
            this(policyId, policyCode, policyName, scope, null, discountType, tierMinQuantity, discountValue, discountPerUnit, discountAmount);
        }
    }
}
