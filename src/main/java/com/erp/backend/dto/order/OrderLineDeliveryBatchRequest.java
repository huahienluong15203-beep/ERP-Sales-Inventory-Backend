package com.erp.backend.dto.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * S5-01: Yêu cầu cập nhật hàng loạt số lượng thực giao của các dòng trong đơn hàng.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderLineDeliveryBatchRequest {

    @NotEmpty(message = "Danh sách dòng hàng giao không được để trống")
    @Valid
    private List<OrderLineDeliveryRequest> lineDeliveries;
}
