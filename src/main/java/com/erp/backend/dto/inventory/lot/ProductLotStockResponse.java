package com.erp.backend.dto.inventory.lot;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Số liệu tồn chi tiết theo từng lô của sản phẩm (S6-01)")
public class ProductLotStockResponse {

    private Long productId;
    private String productSku;
    private String productName;
    private String baseUnit;
    private Boolean hasBatchManagement;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;

    private BigDecimal totalStock;
    private BigDecimal validStock; // Tồn còn hạn sử dụng
    private BigDecimal nearExpiryStock; // Tồn sắp hết hạn
    private BigDecimal expiredStock; // Tồn đã quá hạn

    private List<ProductLotResponse> lots;
}
