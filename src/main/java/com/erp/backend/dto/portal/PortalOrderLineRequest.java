package com.erp.backend.dto.portal;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** S4-10: Một dòng trong giỏ hàng của đại lý. Không có ô giá: giá luôn lấy theo bảng giá hiện hành. */
@Getter
@Setter
public class PortalOrderLineRequest {
    private String productSku;
    private String unitName;
    private BigDecimal quantity;
}
