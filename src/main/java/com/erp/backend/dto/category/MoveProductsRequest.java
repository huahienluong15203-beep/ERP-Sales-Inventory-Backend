package com.erp.backend.dto.category;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** S2-06: Chuyển một hoặc nhiều sản phẩm vào nhóm hàng. */
@Getter
@Setter
public class MoveProductsRequest {

    @NotEmpty(message = "Vui lòng chọn ít nhất một sản phẩm")
    @Size(max = 500, message = "Mỗi lần chuyển tối đa 500 sản phẩm")
    private List<Long> productIds;
}
