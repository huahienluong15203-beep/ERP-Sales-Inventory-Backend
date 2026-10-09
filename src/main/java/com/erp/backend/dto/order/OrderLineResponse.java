package com.erp.backend.dto.order;

import java.math.BigDecimal;

/**
 * S3-09 / S4-01 / S4-03: Dòng hàng đã tính tiền kèm thông tin tồn kho.
 * unitPrice: giá trên 1 đơn vị cơ sở; pricePerUnit: giá trên 1 đơn vị đã chọn (= unitPrice x hệ số hoặc giá sửa thủ công).
 * isCustomPrice: cờ đánh dấu đã sửa giá thủ công.
 * isBelowFloor: cờ đánh dấu bán dưới giá sàn.
 * isOverStock: cờ đánh dấu đặt vượt tồn khả dụng.
 * maxAllowedQuantity: số lượng tối đa còn đặt được theo ĐVT đã chọn.
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
        BigDecimal netAmount,
        Boolean isCustomPrice,
        Boolean isBelowFloor,
        String warehouseCode,
        String warehouseName,
        BigDecimal physicalStock,
        BigDecimal reservedStock,
        BigDecimal availableStock,
        Boolean isOverStock,
        BigDecimal maxAllowedQuantity) {

    public OrderLineResponse(Long id, Integer lineNo, Long productId, String productSku,
                             String productName, String unitName, BigDecimal conversionFactor,
                             BigDecimal quantity, String baseUnit, BigDecimal baseQuantity,
                             String priceListCode, BigDecimal unitPrice, BigDecimal pricePerUnit,
                             BigDecimal floorPrice, BigDecimal grossAmount, String discountPolicyCode,
                             BigDecimal discountAmount, BigDecimal netAmount,
                             Boolean isCustomPrice, Boolean isBelowFloor) {
        this(id, lineNo, productId, productSku, productName, unitName, conversionFactor,
                quantity, baseUnit, baseQuantity, priceListCode, unitPrice, pricePerUnit,
                floorPrice, grossAmount, discountPolicyCode, discountAmount, netAmount,
                isCustomPrice, isBelowFloor, null, null, null, null, null, false, null);
    }

    public OrderLineResponse(Long id, Integer lineNo, Long productId, String productSku,
                             String productName, String unitName, BigDecimal conversionFactor,
                             BigDecimal quantity, String baseUnit, BigDecimal baseQuantity,
                             String priceListCode, BigDecimal unitPrice, BigDecimal pricePerUnit,
                             BigDecimal floorPrice, BigDecimal grossAmount, String discountPolicyCode,
                             BigDecimal discountAmount, BigDecimal netAmount) {
        this(id, lineNo, productId, productSku, productName, unitName, conversionFactor,
                quantity, baseUnit, baseQuantity, priceListCode, unitPrice, pricePerUnit,
                floorPrice, grossAmount, discountPolicyCode, discountAmount, netAmount,
                false, false, null, null, null, null, null, false, null);
    }
}
