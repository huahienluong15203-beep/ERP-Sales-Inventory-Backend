package com.erp.backend.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * S4-09 / SCRUM-159: Yêu cầu sao chép đơn cũ thành đơn mới.
 * Cho phép tùy chọn cập nhật ngày giao, ghi chú, điểm giao hàng (nếu để trống sẽ tự động lấy theo đơn gốc).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Yêu cầu sao chép đơn hàng cũ thành đơn mới (S4-09)")
public class OrderCopyRequest {

    @Schema(description = "Ghi chú đơn hàng mới (để trống sẽ tự động lấy ghi chú đơn cũ kèm nguồn)", example = "Đơn giao định kỳ tuần 2")
    private String note;

    @Schema(description = "Ngày giao mong muốn (để trống sẽ mặc định là ngày mai)", example = "2026-10-15")
    private LocalDate desiredDeliveryDate;

    @Schema(description = "ID điểm giao hàng mới (để trống sẽ kế thừa điểm giao của đơn cũ hoặc điểm mặc định)", example = "1")
    private Long deliveryAddressId;
}
