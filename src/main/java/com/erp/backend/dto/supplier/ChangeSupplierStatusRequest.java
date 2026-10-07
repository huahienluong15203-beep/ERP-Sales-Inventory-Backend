package com.erp.backend.dto.supplier;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** S2-09: Ngừng giao dịch (INACTIVE, bắt buộc lý do) hoặc giao dịch lại (ACTIVE). */
@Getter
@Setter
public class ChangeSupplierStatusRequest {

    @NotBlank(message = "Vui lòng chọn trạng thái")
    @Pattern(regexp = "ACTIVE|INACTIVE", message = "Trạng thái chỉ nhận ACTIVE hoặc INACTIVE")
    private String status;

    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    private String reason;
}
