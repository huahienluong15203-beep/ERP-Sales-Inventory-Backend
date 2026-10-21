package com.erp.backend.dto.warehouse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * S5-03: Cập nhật thông tin kho hàng.
 * Mã kho (code) mang tính định danh hệ thống, không thay đổi sau khi tạo.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseUpdateRequest {

    @NotBlank(message = "Tên kho không được để trống")
    @Size(max = 150, message = "Tên kho tối đa 150 ký tự")
    private String name;

    @Size(max = 255, message = "Địa chỉ kho tối đa 255 ký tự")
    private String address;

    private Long managerId;

    private String status;
}
