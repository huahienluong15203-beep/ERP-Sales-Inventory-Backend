package com.erp.backend.dto.salesreturn;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * S8-01: DTO thông tin phiếu trả hàng từ đại lý.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesReturnResponse {
    private Long id;
    private String code;
    private LocalDate returnDate;
    private Long invoiceId;
    private String invoiceCode;
    private Long customerId;
    private String customerCode;
    private String customerName;
    private Long receivingWarehouseId;
    private String warehouseCode;
    private String warehouseName;
    private String returnReason;
    private String returnReasonLabel;
    private String reasonDescription;
    private String status;
    private String statusLabel;
    private BigDecimal totalQuantity;
    private BigDecimal totalAmount;
    private String notes;
    private Long createdById;
    private String createdByName;
    private Long receivedById;
    private String receivedByName;
    private LocalDateTime receivedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<SalesReturnLineResponse> lines;
}
