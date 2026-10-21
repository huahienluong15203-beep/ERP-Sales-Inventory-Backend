package com.erp.backend.dto.inventory.stockcard;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S6-02: Một dòng thẻ kho. Số lượng theo ĐVT cơ sở.
 * quantityIn / quantityOut tách sẵn để hiển thị 2 cột Nhập / Xuất (luôn >= 0); balanceAfter = tồn sau nghiệp vụ.
 * docType + docId + docCode để mở chứng từ gốc.
 */
public record StockCardRow(
        Long id,
        LocalDateTime movementAt,
        String movementType,
        String movementTypeLabel,
        Long warehouseId,
        String warehouseCode,
        Long productId,
        String productSku,
        String productName,
        String baseUnit,
        BigDecimal quantityIn,
        BigDecimal quantityOut,
        BigDecimal balanceAfter,
        String docType,
        Long docId,
        String docCode,
        String note,
        String actorUsername) {
}
