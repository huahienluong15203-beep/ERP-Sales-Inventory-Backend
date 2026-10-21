package com.erp.backend.dto.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * S6-05: Xác nhận xuất kho đơn đang soạn hàng (PICKING).
 * - lines: chỉ cần gửi các dòng xuất thiếu; dòng không gửi = xuất đủ.
 * - Xuất thiếu thì bắt buộc lý do (ở dòng hoặc ở note).
 * - createBackorder (mặc định true): phần còn thiếu tách thành đơn nợ hàng để giao lần sau;
 *   false = bỏ phần thiếu (đại lý không lấy nữa), tiền đơn giảm theo.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDispatchRequest {

    @Valid
    private List<OrderLineDispatchRequest> lines;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String note;

    private Boolean createBackorder;
}
