package com.erp.backend.dto.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** S3-03: Ngừng giao dịch (INACTIVE) hoặc giao dịch lại (ACTIVE). Ngừng giao dịch bắt buộc nhập lý do. */
@Getter
@Setter
public class ChangeCustomerStatusRequest {

    @NotBlank(message = "Vui lòng chọn trạng thái")
    @Pattern(regexp = "ACTIVE|INACTIVE", message = "Trạng thái chỉ nhận ACTIVE hoặc INACTIVE")
    private String status;

    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    private String reason;
}
