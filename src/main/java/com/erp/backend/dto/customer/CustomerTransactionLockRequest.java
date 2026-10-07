package com.erp.backend.dto.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * S3-07: Khóa hoặc mở giao dịch với một đại lý.
 * - Bắt buộc nhập lý do khi khóa hoặc mở giao dịch.
 * - Đại lý bị khóa không tạo được đơn mới trên mọi nền tảng.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerTransactionLockRequest {

    @NotNull(message = "Vui lòng chọn trạng thái khóa hoặc mở giao dịch")
    private Boolean locked;

    @NotBlank(message = "Bắt buộc nhập lý do khi khóa hoặc mở giao dịch")
    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    private String reason;
}
