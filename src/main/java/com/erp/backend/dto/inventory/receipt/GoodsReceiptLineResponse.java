package com.erp.backend.dto.inventory.receipt;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoodsReceiptLineResponse {

    private String id;
    private Long productId;
    private String productSku;
    private String productName;
    private String category;
    private String baseUnit;
    private String selectedUnit;
    private BigDecimal conversionFactor;
    private BigDecimal quantity;
    private BigDecimal baseQuantity;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private String batchNumber;
    private String expiredDate;
    private Boolean hasBatchManagement;
    private String note;
}
