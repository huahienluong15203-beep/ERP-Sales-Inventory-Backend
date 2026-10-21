package com.erp.backend.dto.salesreturn;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * S8-01: DTO yêu cầu lập phiếu trả hàng từ đại lý.
 * - Chọn hoá đơn gốc (invoiceId).
 * - Bắt buộc chọn lý do trả (returnReason).
 * - Chọn danh sách dòng hàng và số lượng trả.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesReturnCreateRequest {

    @NotNull(message = "Hoá đơn gốc không được để trống")
    private Long invoiceId;

    @NotNull(message = "Kho nhận hàng trả không được để trống")
    private Long receivingWarehouseId;

    /** Bắt buộc chọn lý do trả (AC1). */
    @NotBlank(message = "Bắt buộc chọn lý do trả hàng")
    private String returnReason;

    private String reasonDescription;

    private LocalDate returnDate;

    private String notes;

    @NotEmpty(message = "Danh sách mặt hàng trả không được để trống")
    @Valid
    private List<SalesReturnItemRequest> items;
}
