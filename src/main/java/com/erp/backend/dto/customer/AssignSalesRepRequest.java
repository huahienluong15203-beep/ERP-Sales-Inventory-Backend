package com.erp.backend.dto.customer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** S3-06: Gán / đổi nhân viên phụ trách chính cho một đại lý. */
@Getter
@Setter
public class AssignSalesRepRequest {

    @NotNull(message = "Vui lòng chọn nhân viên phụ trách")
    private Long salesRepId;

    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    private String reason;
}
