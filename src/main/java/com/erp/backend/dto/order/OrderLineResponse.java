package com.erp.backend.dto.order;

import java.math.BigDecimal;

/**
 * S3-09: Dòng hàng đã tính tiền.
 * unitPrice: giá trên 1 đơn vị cơ sở; pricePerUnit: giá trên 1 đơn vị đã chọn (= unitPrice x hệ số).
 */
public record OrderLineResponse(
        Long id,
        Integer lineNo,
        Long productId,
        String productSku,
        String productName,
        String unitName,
        BigDecimal conversionFactor,
        BigDecimal quantity,
        String baseUnit,
        BigDecimal baseQuantity,
        String priceListCode,
        BigDecimal unitPrice,
        BigDecimal pricePerUnit,
        BigDecimal floorPrice,
        BigDecimal grossAmount,
        String discountPolicyCode,
        BigDecimal discountAmount,
        BigDecimal netAmount) {
}
