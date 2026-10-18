package com.erp.backend.dto.salesreturn;

import lombok.*;

import java.math.BigDecimal;

/**
 * S8-01: DTO thông tin dòng hàng trên hoá đơn kèm số lượng còn có thể trả lại.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnableInvoiceLineResponse {
    private Long invoiceLineId;
    private Integer lineNo;
    private Long productId;
    private String productSku;
    private String productName;
    private String unitName;
    private BigDecimal conversionFactor;
    private BigDecimal deliveredQuantity;
    private BigDecimal previouslyReturnedQuantity;
    private BigDecimal remainingReturnableQuantity;
    private BigDecimal pricePerUnit;
    private Boolean hasBatchManagement;
    private String batchNumber;
    private java.time.LocalDate manufactureDate;
    private java.time.LocalDate expiredDate;
}
