package com.erp.backend.dto.inventory.lot;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu cập nhật thông tin lô hàng (S6-01)")
public class ProductLotUpdateRequest {

    @Schema(description = "Ngày sản xuất")
    private LocalDate manufactureDate;

    @Schema(description = "Hạn sử dụng")
    private LocalDate expiredDate;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String notes;

    @Schema(description = "Trạng thái lô: ACTIVE hoặc LOCKED")
    private String status;
}
