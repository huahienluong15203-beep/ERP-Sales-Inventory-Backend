package com.erp.backend.dto.warehouse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * S5-03: Yêu cầu khai báo vị trí lưu trong kho (ở mức kệ hoặc khu).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseLocationCreateRequest {

    @NotBlank(message = "Mã vị trí không được để trống")
    @Size(max = 50, message = "Mã vị trí tối đa 50 ký tự")
    @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "Mã vị trí chỉ gồm chữ, số, dấu chấm, gạch ngang và gạch dưới")
    private String code;

    @NotBlank(message = "Tên vị trí không được để trống")
    @Size(max = 150, message = "Tên vị trí tối đa 150 ký tự")
    private String name;

    // ZONE (Khu), RACK (Kệ), SHELF (Giá), BIN (Ô/Ngăn)
    private String locationType;

    @Size(max = 100, message = "Tên phân khu tối đa 100 ký tự")
    private String zone;

    @Size(max = 100, message = "Tên kệ tối đa 100 ký tự")
    private String shelf;

    @Size(max = 500, message = "Mô tả tối đa 500 ký tự")
    private String description;

    private String status;
}
