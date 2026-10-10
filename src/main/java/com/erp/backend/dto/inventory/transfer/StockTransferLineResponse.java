package com.erp.backend.dto.inventory.transfer;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockTransferLineResponse {

    private String id;
    private Long productId;
    private String productSku;
    private String productName;
    private String category;
    private String unit;
    private BigDecimal sourceAvailableStock;
    private BigDecimal transferQuantity;
    private BigDecimal receivedQuantity;
    private BigDecimal differenceQuantity;
    private String discrepancyReason;
    private String batchNumber;
    private String expiredDate;
}
