package com.erp.backend.dto.pricing;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** S2-10: Một dòng giá gửi lên. productName chỉ để hiển thị, hệ thống lấy tên theo sản phẩm thật. */
@Getter
@Setter
public class PriceListItemRequest {
    private String productSku;
    private String productName;
    private BigDecimal price;
    private BigDecimal floorPrice;
}
