package com.erp.backend.dto.discount;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** S3-01: Tính chiết khấu cho một dòng hàng. quantity theo đơn vị cơ sở; unitPrice là giá bán trên đơn vị cơ sở. */
@Getter
@Setter
public class DiscountCalculationRequest {
    private String productSku;
    private String customerGroup;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private LocalDate date;
}
