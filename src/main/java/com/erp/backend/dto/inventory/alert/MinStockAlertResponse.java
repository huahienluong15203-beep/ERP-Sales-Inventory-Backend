package com.erp.backend.dto.inventory.alert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * S5-09: DTO trả về cấu hình tồn tối thiểu và trạng thái cảnh báo đứt hàng.
 * Khớp 100% với giao diện Frontend (stockAlert.ts).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MinStockAlertResponse {
    private String id;
    private Long productId;
    private String productSku;
    private String productName;
    private String category;
    private String unit;
    private String warehouseCode;
    private String warehouseName;
    private BigDecimal minThreshold;
    private BigDecimal currentStock;
    private BigDecimal availableStock;
    private boolean isBelowThreshold;
    private BigDecimal deficitQuantity;
    private String severity; // CRITICAL | WARNING | SAFE
    private BigDecimal suggestedReorderQuantity;
    private String supplierCode;
    private String supplierName;
    private String lastRestockedDate;
    private String updatedAt;
}
