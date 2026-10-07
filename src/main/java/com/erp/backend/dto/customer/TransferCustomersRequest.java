package com.erp.backend.dto.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * S3-06: Chuyển giao hàng loạt đại lý từ nhân viên này sang nhân viên khác (vd: nhân viên nghỉ việc).
 * regionId không bắt buộc: có thì chỉ chuyển các đại lý thuộc khu vực đó.
 */
@Getter
@Setter
public class TransferCustomersRequest {

    @NotNull(message = "Vui lòng chọn nhân viên bàn giao")
    private Long fromSalesRepId;

    @NotNull(message = "Vui lòng chọn nhân viên nhận bàn giao")
    private Long toSalesRepId;

    private Long regionId;

    @NotBlank(message = "Vui lòng nhập lý do chuyển giao")
    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    private String reason;
}
