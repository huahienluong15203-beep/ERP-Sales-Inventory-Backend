package com.erp.backend.dto.inventory.receipt;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoodsReceiptLineCreateRequest {

    @NotNull(message = "Mã sản phẩm (productId) không được để trống")
    private Long productId;

    private String productSku;

    private String productName;

    private String category;

    private String baseUnit;

    @NotBlank(message = "Đơn vị tính chọn nhập (selectedUnit) không được để trống")
    private String selectedUnit;

    @NotNull(message = "Hệ số quy đổi không được để trống")
    @DecimalMin(value = "0.0001", message = "Hệ số quy đổi phải lớn hơn 0")
    private BigDecimal conversionFactor;

    @NotNull(message = "Số lượng nhập không được để trống")
    @DecimalMin(value = "0.0001", message = "Số lượng nhập phải lớn hơn 0")
    private BigDecimal quantity;

    private BigDecimal baseQuantity;

    private BigDecimal unitPrice;

    private BigDecimal totalAmount;

    // Số lô sản xuất (AC3)
    private String batchNumber;

    // Hạn sử dụng (YYYY-MM-DD)
    private String expiredDate;

    private Boolean hasBatchManagement;

    private String note;
}
