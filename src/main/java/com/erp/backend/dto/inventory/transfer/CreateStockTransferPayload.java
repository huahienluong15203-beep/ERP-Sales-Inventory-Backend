package com.erp.backend.dto.inventory.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateStockTransferPayload {

    @NotBlank(message = "Vui lòng chọn Kho xuất (kho nguồn)")
    private String sourceWarehouseCode;

    @NotBlank(message = "Vui lòng chọn Kho nhận (kho đích)")
    private String destWarehouseCode;

    @NotBlank(message = "Ngày điều chuyển không được để trống")
    private String transferDate;

    private String expectedReceiveDate;

    private String vehiclePlate;

    private String transporterName;

    private String note;

    @Builder.Default
    private String status = "DRAFT";

    @NotEmpty(message = "Phiếu chuyển kho phải có ít nhất 1 dòng hàng hóa")
    @Valid
    private List<CreateStockTransferLinePayload> lines;
}
