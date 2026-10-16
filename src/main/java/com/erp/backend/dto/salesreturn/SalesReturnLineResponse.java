package com.erp.backend.dto.salesreturn;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * S8-01: DTO chi tiết dòng hàng trả trong response.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesReturnLineResponse {
    private Long id;
    private Integer lineNo;
    private Long invoiceLineId;
    private Long productId;
    private String productSku;
    private String productName;
    private String unitName;
    private BigDecimal conversionFactor;
    private BigDecimal deliveredQuantity;
    private BigDecimal previouslyReturnedQuantity;
    private BigDecimal returnQuantity;
    private BigDecimal baseQuantity;
    private BigDecimal pricePerUnit;
    private BigDecimal lineAmount;
    private Boolean hasBatchManagement;
    private String batchNumber;
    private LocalDate manufactureDate;
    private LocalDate expiredDate;
    private Boolean isDamaged;
    private String lineNotes;
}
