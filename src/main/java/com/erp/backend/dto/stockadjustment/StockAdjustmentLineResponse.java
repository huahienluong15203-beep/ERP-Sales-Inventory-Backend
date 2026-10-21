package com.erp.backend.dto.stockadjustment;

import lombok.*;

import java.math.BigDecimal;

/**
 * S8-03: DTO chi tiết dòng hàng điều chỉnh tồn kho.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAdjustmentLineResponse {
    private Long id;
    private Integer lineNo;
    private Long productId;
    private String productSku;
    private String productName;
    private String baseUnit;
    private BigDecimal currentStock;
    private BigDecimal adjustmentQuantity;
    private BigDecimal costPrice;
    private BigDecimal lossValue;
    private Boolean hasBatchManagement;
    private String batchNumber;
    private Long productLotId;
    private String lineNotes;
}
