package com.erp.backend.dto.supplier;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** S2-09: Tạo nhà cung cấp mới. */
@Getter
@Setter
public class CreateSupplierRequest extends SupplierProfileRequest {

    @NotBlank(message = "Mã nhà cung cấp không được để trống")
    @Pattern(regexp = "^\\s*[A-Za-z0-9][A-Za-z0-9_-]{1,29}\\s*$",
            message = "Mã nhà cung cấp gồm 2–30 ký tự: chữ, số, dấu - hoặc _")
    private String code;
}
