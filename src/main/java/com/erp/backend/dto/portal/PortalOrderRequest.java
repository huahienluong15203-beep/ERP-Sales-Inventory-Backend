package com.erp.backend.dto.portal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** S4-10: Giỏ hàng đại lý gửi lên để xem trước hoặc đặt đơn. Đại lý luôn lấy theo tài khoản đăng nhập. */
@Getter
@Setter
public class PortalOrderRequest {

    private Long deliveryAddressId;

    private LocalDate desiredDeliveryDate;

    @Size(max = 400, message = "Ghi chú tối đa 400 ký tự")
    private String note;

    @Valid
    private List<PortalOrderLineRequest> lines = new ArrayList<>();
}
