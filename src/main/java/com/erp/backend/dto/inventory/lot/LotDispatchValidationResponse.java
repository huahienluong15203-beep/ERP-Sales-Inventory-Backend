package com.erp.backend.dto.inventory.lot;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Kết quả kiểm tra HSD & xuất kho theo lô (S6-01)")
public class LotDispatchValidationResponse {

    private boolean valid;
    private boolean expired;
    private boolean nearExpiry;
    private boolean locked;
    private boolean insufficientQuantity;
    private boolean overrideApplied;
    private String message;
    private String batchNumber;
    private LocalDate expiredDate;
    private Long daysToExpiration;
    private BigDecimal availableQuantity;
    private BigDecimal requestedQuantity;
}
