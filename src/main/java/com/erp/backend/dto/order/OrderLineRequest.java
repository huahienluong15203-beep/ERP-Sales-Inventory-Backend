package com.erp.backend.dto.order;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * S3-09: Một dòng hàng gửi lên. unitName để trống = đơn vị cơ sở.
 * S4-01: unitPrice là đơn giá thủ công theo đơn vị đã chọn (unitName), nếu null = lấy giá bảng giá đang hiệu lực.
 */
@Getter
@Setter
public class OrderLineRequest {
    private String productSku;
    private String unitName;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private Boolean isCustomPrice;
}
