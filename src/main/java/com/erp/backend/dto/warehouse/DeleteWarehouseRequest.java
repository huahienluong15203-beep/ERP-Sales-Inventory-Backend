package com.erp.backend.dto.warehouse;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeleteWarehouseRequest {

    @NotBlank(message = "Vui lòng nhập lý do xóa kho hàng")
    private String reason;
}
