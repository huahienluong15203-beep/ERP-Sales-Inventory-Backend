package com.erp.backend.dto.salesreturn;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * S8-01: DTO thông tin một dòng hàng muốn trả trong phiếu trả hàng.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesReturnItemRequest {

    @NotNull(message = "Dòng hoá đơn gốc không được để trống")
    private Long invoiceLineId;

    @NotNull(message = "Số lượng trả không được để trống")
    @DecimalMin(value = "0.0001", message = "Số lượng trả phải lớn hơn 0")
    private BigDecimal returnQuantity;

    /** Bắt buộc nhập nếu sản phẩm có quản lý theo lô (AC3). */
    private String batchNumber;

    private LocalDate manufactureDate;

    private LocalDate expiredDate;

    /** Đánh dấu hàng lỗi / hỏng để nhập vào kho hàng lỗi hoặc khóa xuất bán (AC3). */
    @Builder.Default
    private Boolean isDamaged = false;

    private String lineNotes;
}
