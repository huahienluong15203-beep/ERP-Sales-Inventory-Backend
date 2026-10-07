package com.erp.backend.dto.order;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** S3-09: Một dòng hàng gửi lên. unitName để trống = đơn vị cơ sở. */
@Getter
@Setter
public class OrderLineRequest {
    private String productSku;
    private String unitName;
    private BigDecimal quantity;
}
