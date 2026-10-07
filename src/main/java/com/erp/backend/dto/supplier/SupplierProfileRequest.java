package com.erp.backend.dto.supplier;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/** S2-09: Thông tin nhà cung cấp dùng chung cho tạo mới và cập nhật (không chứa mã — mã không đổi sau khi tạo). */
@Getter
@Setter
public class SupplierProfileRequest {

    @NotBlank(message = "Tên nhà cung cấp không được để trống")
    @Size(max = 200, message = "Tên nhà cung cấp tối đa 200 ký tự")
    private String name;

    @NotBlank(message = "Mã số thuế không được để trống")
    @Pattern(regexp = "^\\s*\\d{10}(-\\d{3})?\\s*$", message = "Mã số thuế phải gồm 10 số hoặc 10 số kèm -3 số chi nhánh")
    private String taxCode;

    @Size(max = 100, message = "Tên người liên hệ tối đa 100 ký tự")
    private String contactName;

    @Pattern(regexp = "^$|^(0|\\+84)(3|5|7|8|9)\\d{8}$", message = "Số điện thoại Việt Nam không hợp lệ")
    private String phone;

    @Email(message = "Email không đúng định dạng")
    @Size(max = 100, message = "Email tối đa 100 ký tự")
    private String email;

    @Size(max = 500, message = "Địa chỉ tối đa 500 ký tự")
    private String address;

    @Size(max = 255, message = "Điều khoản thanh toán tối đa 255 ký tự")
    private String paymentTerms;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String note;
}
