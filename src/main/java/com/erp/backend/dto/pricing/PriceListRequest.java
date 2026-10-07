package com.erp.backend.dto.pricing;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * S2-10: Tạo / sửa bảng giá. Khi tạo phiên bản mới, các trường để trống sẽ kế thừa từ bảng giá gốc.
 * Kiểm tra dữ liệu làm ở service để thông báo lỗi rõ ràng cho từng trường hợp.
 */
@Getter
@Setter
public class PriceListRequest {
    private String code;
    private String name;
    private String customerGroup;
    private LocalDate startDate;
    private LocalDate endDate;
    private String note;
    private List<PriceListItemRequest> items;
}
