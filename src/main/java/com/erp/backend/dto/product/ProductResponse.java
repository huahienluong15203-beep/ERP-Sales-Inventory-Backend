package com.erp.backend.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO phản hồi thông tin sản phẩm (EP-02).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Thông tin sản phẩm")
public class ProductResponse {

    private Long id;
    private String sku;
    private String name;
    private String category;

    // S2-06: id nhóm hàng trong cây (null nếu chưa gắn vào cây)
    private Long categoryId;
    private String baseUnit;
    private String packaging;
    private BigDecimal costPrice;
    private String status;
    private String barcode;
    private String imageUrl;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
