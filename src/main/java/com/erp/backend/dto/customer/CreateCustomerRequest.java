package com.erp.backend.dto.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** S3-03: Tạo đại lý mới. Có thể gán luôn người phụ trách (không bắt buộc). */
@Getter
@Setter
public class CreateCustomerRequest extends CustomerProfileRequest {

    @NotBlank(message = "Mã đại lý không được để trống")
    @Pattern(regexp = "^\\s*[A-Za-z0-9][A-Za-z0-9_-]{1,29}\\s*$",
            message = "Mã đại lý gồm 2–30 ký tự: chữ, số, dấu - hoặc _")
    private String code;

    private Long salesRepId;
}
