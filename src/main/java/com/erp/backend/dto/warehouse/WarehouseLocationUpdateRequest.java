package com.erp.backend.dto.warehouse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * S5-03: Cập nhật thông tin vị trí lưu trong kho.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseLocationUpdateRequest {

    @NotBlank(message = "Tên vị trí không được để trống")
    @Size(max = 150, message = "Tên vị trí tối đa 150 ký tự")
    private String name;

    private String locationType;

    @Size(max = 100, message = "Tên phân khu tối đa 100 ký tự")
    private String zone;

    @Size(max = 100, message = "Tên kệ tối đa 100 ký tự")
    private String shelf;

    @Size(max = 500, message = "Mô tả tối đa 500 ký tự")
    private String description;

    private String status;
}
