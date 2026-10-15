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
}
