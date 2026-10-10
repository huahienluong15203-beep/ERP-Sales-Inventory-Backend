package com.erp.backend.dto.warehouse;

import com.erp.backend.dto.user.RefItem;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * S5-03: DTO chi tiết kho hàng trả về cho Frontend và các phân hệ khác.
 * - Mã, tên, địa chỉ, người phụ trách, trạng thái
 * - Số lượng vị trí lưu trong kho (kệ / khu)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseResponse {

    private Long id;
    private String code;
    private String name;
    private String address;
    private String status;
    private RefItem manager;
    private String managerPhone;
    private String managerEmail;
    private long locationCount;
    private long activeLocationCount;
    private List<WarehouseLocationResponse> locations;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
