package com.erp.backend.dto.inventory.lot;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Thông tin chi tiết Lô hàng & Hạn sử dụng (S6-01)")
public class ProductLotResponse {

    private Long id;
    private Long productId;
    private String productSku;
    private String productName;
    private String productBaseUnit;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private String batchNumber;
    private LocalDate manufactureDate;
    private LocalDate expiredDate;
    private BigDecimal quantity;
    private Long supplierId;
    private String supplierCode;
    private String supplierName;
    private String notes;
    private String status; // ACTIVE, LOCKED

    // Số ngày còn lại đến hạn sử dụng (< 0: đã hết hạn, = 0: hết hạn hôm nay, > 0: còn hạn)
    private Long daysToExpiration;

    // Trạng thái hạn dùng: SAFE (An toàn), NEAR_EXPIRATION (Sắp hết hạn), EXPIRED (Đã quá hạn), OUT_OF_STOCK (Hết tồn)
    private String expiryStatus;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
