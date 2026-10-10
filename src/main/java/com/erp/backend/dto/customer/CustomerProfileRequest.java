package com.erp.backend.dto.customer;

import com.erp.backend.entity.CustomerGroup;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/**
 * S3-03: Thông tin hồ sơ đại lý dùng chung cho tạo mới và cập nhật.
 * Không chứa mã đại lý (không đổi sau khi tạo) và người phụ trách (đổi qua API phân công S3-06).
 */
@Getter
@Setter
public class CustomerProfileRequest {

    @NotBlank(message = "Tên đại lý không được để trống")
    @Size(max = 200, message = "Tên đại lý tối đa 200 ký tự")
    private String name;

    @Pattern(regexp = "^$|^\\d{10}(-\\d{3})?$", message = "Mã số thuế phải gồm 10 số hoặc 10 số kèm -3 số chi nhánh")
    private String taxCode;

    @NotNull(message = "Vui lòng chọn nhóm khách hàng")
    private CustomerGroup customerGroup;

    @NotNull(message = "Vui lòng chọn khu vực")
    private Long regionId;

    @Size(max = 100, message = "Tên người liên hệ tối đa 100 ký tự")
    private String contactName;

    @Pattern(regexp = "^$|^(0|\\+84)(3|5|7|8|9)\\d{8}$", message = "Số điện thoại Việt Nam không hợp lệ")
    private String phone;

    @Email(message = "Email không đúng định dạng")
    @Size(max = 100, message = "Email tối đa 100 ký tự")
    private String email;

    @Size(max = 500, message = "Địa chỉ tối đa 500 ký tự")
    private String address;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String note;

    // S5-03: Kho phục vụ mặc định của đại lý (tuỳ chọn)
    private Long defaultWarehouseId;
}
