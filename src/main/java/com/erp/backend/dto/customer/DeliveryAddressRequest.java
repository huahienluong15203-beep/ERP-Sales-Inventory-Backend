package com.erp.backend.dto.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** S3-04: Thêm / sửa điểm giao hàng của đại lý. */
@Getter
@Setter
public class DeliveryAddressRequest {

    @Size(max = 100, message = "Tên gợi nhớ tối đa 100 ký tự")
    private String label;

    @NotBlank(message = "Địa chỉ giao hàng không được để trống")
    @Size(max = 500, message = "Địa chỉ tối đa 500 ký tự")
    private String address;

    @NotBlank(message = "Tên người nhận không được để trống")
    @Size(max = 100, message = "Tên người nhận tối đa 100 ký tự")
    private String receiverName;

    @NotBlank(message = "Số điện thoại người nhận không được để trống")
    @Pattern(regexp = "^(0|\\+84)(3|5|7|8|9)\\d{8}$", message = "Số điện thoại Việt Nam không hợp lệ")
    private String receiverPhone;

    @Size(max = 500, message = "Ghi chú đường đi tối đa 500 ký tự")
    private String note;

    /** true: đặt điểm này làm mặc định. Điểm giao đầu tiên luôn tự thành mặc định. */
    private Boolean isDefault;
}
