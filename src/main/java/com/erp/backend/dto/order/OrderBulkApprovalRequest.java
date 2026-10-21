package com.erp.backend.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Góp ý GV mục 8: tick chọn nhiều đơn (hoặc tất cả) rồi Duyệt / Từ chối / Trả lại một lần. */
@Getter
@Setter
public class OrderBulkApprovalRequest {

    /** APPROVE, REJECT hoặc RETURN. */
    @NotBlank(message = "Vui lòng chọn thao tác")
    private String action;

    @NotEmpty(message = "Vui lòng chọn ít nhất 1 đơn hàng")
    @Size(max = 100, message = "Mỗi lần xử lý tối đa 100 đơn")
    private List<Long> orderIds;

    /** Bắt buộc khi Từ chối / Trả lại. */
    @Size(max = 500, message = "Ý kiến tối đa 500 ký tự")
    private String comment;
}
