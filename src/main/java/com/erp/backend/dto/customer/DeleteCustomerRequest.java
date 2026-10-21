package com.erp.backend.dto.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Xoá hồ sơ đại lý: bắt buộc nhập lý do để ghi nhật ký. */
@Getter
@Setter
@NoArgsConstructor
public class DeleteCustomerRequest {

    @NotBlank(message = "Vui lòng nhập lý do xoá đại lý")
    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    private String reason;
}
