package com.erp.backend.dto.warehouse;

import lombok.*;

/**
 * S5-03: Gán kho phục vụ mặc định cho đại lý.
 * warehouseId: null nếu muốn gỡ bỏ kho mặc định.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssignDefaultWarehouseRequest {

    private Long warehouseId;
}
