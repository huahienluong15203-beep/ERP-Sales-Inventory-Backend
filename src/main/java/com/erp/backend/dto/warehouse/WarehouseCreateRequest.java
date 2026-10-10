package com.erp.backend.dto.warehouse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * S5-03: Yêu cầu khai báo mới một kho hàng trong hệ thống.
 * - Mã kho (code): duy nhất, vd WH-MB01, KHO-HN
 * - Tên kho (name): bắt buộc
 * - Địa chỉ (address): địa chỉ kho
 * - Người phụ trách (managerId): id của nhân sự phụ trách kho
 * - Trạng thái (status): ACTIVE / INACTIVE
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseCreateRequest {

    @NotBlank(message = "Mã kho không được để trống")
    @Size(max = 30, message = "Mã kho tối đa 30 ký tự")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Mã kho chỉ gồm chữ, số, gạch ngang và gạch dưới")
    private String code;

    @NotBlank(message = "Tên kho không được để trống")
    @Size(max = 150, message = "Tên kho tối đa 150 ký tự")
    private String name;

    @Size(max = 255, message = "Địa chỉ kho tối đa 255 ký tự")
    private String address;

    private Long managerId;

    private String status;
}
