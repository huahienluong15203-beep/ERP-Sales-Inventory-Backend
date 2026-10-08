package com.erp.backend.dto.order;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** S4-05: Ý kiến khi Duyệt (không bắt buộc), Từ chối / Trả lại sửa (bắt buộc, kiểm tra ở service). */
@Getter
@Setter
public class OrderApprovalActionRequest {

    @Size(max = 500, message = "Ý kiến tối đa 500 ký tự")
    private String comment;
}
