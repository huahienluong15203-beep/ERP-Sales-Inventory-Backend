package com.erp.backend.dto.inventory.receipt;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoodsReceiptCreateRequest {

    @NotNull(message = "Vui lòng chọn nhà cung cấp")
    private Long supplierId;

    private String supplierCode;

    private String supplierName;

    @NotBlank(message = "Số chứng từ / hóa đơn nhà cung cấp không được để trống")
    private String documentNumber;

    @NotBlank(message = "Ngày nhập kho không được để trống")
    private String receiptDate;

    @NotBlank(message = "Vui lòng chọn kho tiếp nhận")
    private String warehouseCode;

    private String warehouseName;

    private String vehiclePlate;

    private String driverName;

    private String note;

    @Builder.Default
    private String status = "DRAFT";

    @NotEmpty(message = "Phiếu nhập kho phải có ít nhất 1 dòng hàng hóa")
    @Valid
    private List<GoodsReceiptLineCreateRequest> lines;
}
