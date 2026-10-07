package com.erp.backend.dto.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** S2-06: Sửa nhóm hàng (tên, mô tả). Mã và vị trí trong cây không đổi. */
@Getter
@Setter
public class CategoryRequest {

    @NotBlank(message = "Tên nhóm hàng không được để trống")
    @Size(max = 100, message = "Tên nhóm hàng tối đa 100 ký tự")
    private String name;

    @Size(max = 500, message = "Mô tả tối đa 500 ký tự")
    private String description;
}
