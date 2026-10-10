package com.erp.backend.dto.inventory.receipt;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoodsReceiptResponse {

    private Long id;
    private String code;
    private Long supplierId;
    private String supplierCode;
    private String supplierName;
    private String documentNumber;
    private String receiptDate;
    private String warehouseCode;
    private String warehouseName;
    private String status;
    private List<GoodsReceiptLineResponse> lines;
    private Integer totalLines;
    private BigDecimal totalBaseQuantity;
    private BigDecimal totalAmount;
    private String vehiclePlate;
    private String driverName;
    private String note;
    private String createdByUsername;
    private String confirmedAt;
    private String confirmedByUsername;
    private String createdAt;
    private String updatedAt;
}
