package com.erp.backend.dto.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** S2-06: Tạo nhóm hàng. parentId = null là tạo nhóm gốc (cấp 1). */
@Getter
@Setter
public class CreateCategoryRequest extends CategoryRequest {

    @NotBlank(message = "Mã nhóm hàng không được để trống")
    @Pattern(regexp = "^\\s*[A-Za-z0-9][A-Za-z0-9_-]{1,29}\\s*$",
            message = "Mã nhóm hàng gồm 2–30 ký tự: chữ, số, dấu - hoặc _")
    private String code;

    private Long parentId;
}
