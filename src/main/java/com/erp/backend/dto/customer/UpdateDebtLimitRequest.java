package com.erp.backend.dto.customer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

/**
 * S3-05: Khai báo hạn mức công nợ và số ngày nợ tối đa cho phép.
 * Bắt buộc nhập lý do khi thay đổi để ghi nhật ký hệ thống.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateDebtLimitRequest {

    @NotNull(message = "Vui lòng nhập hạn mức tiền tối đa")
    @DecimalMin(value = "0", message = "Hạn mức tiền tối đa không được nhỏ hơn 0")
    @jakarta.validation.constraints.Digits(integer = 13, fraction = 2, message = "Hạn mức tiền tối đa không được vượt quá 13 chữ số")
    private BigDecimal creditLimit;

    @NotNull(message = "Vui lòng nhập số ngày nợ tối đa")
    @Min(value = 0, message = "Số ngày nợ tối đa không được nhỏ hơn 0")
    private Integer maxDebtDays;

    @NotBlank(message = "Bắt buộc nhập lý do khi thay đổi hạn mức công nợ")
    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    private String reason;
}
