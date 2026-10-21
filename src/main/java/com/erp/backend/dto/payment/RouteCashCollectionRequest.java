package com.erp.backend.dto.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * S7-05: Yêu cầu ghi nhận thu tiền theo tuyến của Nhân viên kinh doanh.
 * - Khoản thu lưu vết ảnh biên lai tại cửa hàng đại lý.
 * - Ban đầu ở trạng thái PENDING_SUBMISSION (Chờ nộp).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteCashCollectionRequest {

    @NotNull(message = "Vui lòng chọn đại lý")
    private Long customerId;

    @NotNull(message = "Vui lòng nhập số tiền đã thu")
    @DecimalMin(value = "0.01", message = "Số tiền thu phải lớn hơn 0")
    private BigDecimal amount;

    @Builder.Default
    private String paymentMethod = "CASH"; // CASH, BANK_TRANSFER, OTHER

    @NotNull(message = "Vui lòng chọn ngày thu tiền")
    private LocalDate paymentDate;

    @Size(max = 150, message = "Tên người nộp/tài xế không được vượt quá 150 ký tự")
    private String payerName;

    @Size(max = 500, message = "Ghi chú không được vượt quá 500 ký tự")
    private String notes;

    /** Đường dẫn ảnh biên lai hoặc base64 ảnh chụp biên lai/chứng từ */
    private String receiptImageUrl;

    private String receiptImageBase64;
}
