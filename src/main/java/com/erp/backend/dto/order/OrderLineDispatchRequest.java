package com.erp.backend.dto.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** S6-05: Số lượng thực xuất của 1 dòng (theo ĐVT của dòng, vd Thùng). Dòng không gửi lên = xuất đủ. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderLineDispatchRequest {

    @NotNull(message = "Mã dòng hàng không được để trống")
    private Long lineId;

    @NotNull(message = "Số lượng thực xuất không được để trống")
    @DecimalMin(value = "0", message = "Số lượng thực xuất không được âm")
    private BigDecimal dispatchedQuantity;

    @Size(max = 255, message = "Lý do xuất thiếu tối đa 255 ký tự")
    private String shortageReason;

    // S6-04: Số lô xuất kho (nếu rỗng, hệ thống tự động gợi ý theo FEFO)
    @Size(max = 100, message = "Số lô tối đa 100 ký tự")
    private String batchNumber;

    // S6-04: Lý do chọn lô khác lô gợi ý FEFO (bắt buộc khi batchNumber khác lô gợi ý FEFO)
    @Size(max = 500, message = "Lý do đổi lô tối đa 500 ký tự")
    private String lotChangeReason;
}
