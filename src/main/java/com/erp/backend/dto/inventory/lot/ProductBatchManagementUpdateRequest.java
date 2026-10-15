package com.erp.backend.dto.inventory.lot;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu bật/tắt quản lý lô theo từng SKU (S6-01)")
public class ProductBatchManagementUpdateRequest {

    @NotNull(message = "Trạng thái quản lý lô không được để trống")
    @Schema(description = "Bật (true) hoặc tắt (false) quản lý lô cho SKU này")
    private Boolean hasBatchManagement;
}
