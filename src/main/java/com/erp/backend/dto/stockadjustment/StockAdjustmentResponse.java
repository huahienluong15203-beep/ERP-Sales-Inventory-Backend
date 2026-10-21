package com.erp.backend.dto.stockadjustment;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * S8-03: DTO thông tin phiếu điều chỉnh tồn kho.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAdjustmentResponse {
    private Long id;
    private String code;
    private LocalDate adjustmentDate;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private String adjustmentReason;
    private String adjustmentReasonLabel;
    private String reasonDescription;
    private String status;
    private String statusLabel;
    private BigDecimal totalQuantity;
    private BigDecimal totalLossValue;
    private String rejectionReason;
    private String notes;
    private Long createdById;
    private String createdByName;
    private Long approvedById;
    private String approvedByName;
    private LocalDateTime approvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<StockAdjustmentLineResponse> lines;
}
