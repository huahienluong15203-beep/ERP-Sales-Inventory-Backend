package com.erp.backend.dto.warehouse;

import lombok.*;

import java.time.LocalDateTime;

/**
 * S5-03: Thông tin vị trí lưu trữ trong kho (Khu vực / Kệ / Ô).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseLocationResponse {

    private Long id;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private String code;
    private String name;
    private String locationType;
    private String zone;
    private String shelf;
    private String description;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
