package com.erp.backend.dto.trip;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * S6-07: Lập chuyến giao cho các đơn đã xuất kho (cùng 1 kho). Xe rời kho ngay lúc lập chuyến.
 * expectedReturnAt: thời điểm dự kiến giao xong và về kho, quá giờ mà chưa kết thúc chuyến thì báo trễ.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryTripCreateRequest {

    @NotEmpty(message = "Chọn ít nhất 1 đơn đã xuất kho")
    @Size(max = 100, message = "Một chuyến tối đa 100 điểm giao")
    private List<Long> orderIds;

    @Size(max = 20, message = "Biển số xe tối đa 20 ký tự")
    private String vehiclePlate;

    @NotBlank(message = "Vui lòng nhập tên tài xế / người giao")
    @Size(max = 100, message = "Tên người giao tối đa 100 ký tự")
    private String driverName;

    @Size(max = 20, message = "Số điện thoại tối đa 20 ký tự")
    private String driverPhone;

    @NotNull(message = "Vui lòng nhập thời điểm dự kiến xong chuyến")
    private LocalDateTime expectedReturnAt;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String note;
}
