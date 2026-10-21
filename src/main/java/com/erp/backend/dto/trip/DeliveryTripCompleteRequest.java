package com.erp.backend.dto.trip;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** S6-07: Kết thúc chuyến (xe đã về kho, kho nhận lại hàng giao không được). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryTripCompleteRequest {

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String note;
}
