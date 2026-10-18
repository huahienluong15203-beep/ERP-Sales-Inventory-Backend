package com.erp.backend.dto.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * S7-02: Yêu cầu ghi nhận phiếu thu và cấn trừ công nợ.
 * - AC1: Ghi số tiền, hình thức thu, ngày thu, người thu, ghi chú.
 * - AC2: allocationType = FIFO (hoá đơn cũ nhất trước) hoặc SPECIFIC (chỉ định).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentReceiptCreateRequest {

    @NotNull(message = "Vui lòng chọn đại lý")
    private Long customerId;

    @NotNull(message = "Vui lòng nhập số tiền thu")
    @DecimalMin(value = "0.01", message = "Số tiền thu phải lớn hơn 0")
    private BigDecimal amount;

    @NotBlank(message = "Vui lòng chọn hình thức thu")
    private String paymentMethod; // CASH, BANK_TRANSFER, OTHER

    @NotNull(message = "Vui lòng chọn ngày thu tiền")
    private LocalDate paymentDate;

    @NotBlank(message = "Vui lòng nhập tên người thu tiền")
    @Size(max = 100, message = "Tên người thu không được vượt quá 100 ký tự")
    private String collectorName;

    private Long collectorId;

    @Size(max = 500, message = "Ghi chú không được vượt quá 500 ký tự")
    private String notes;

    @Builder.Default
    private String allocationType = "FIFO"; // FIFO hoặc SPECIFIC

    @Valid
    private List<PaymentAllocationItemRequest> specificAllocations;

    private String receiptImageUrl;
}
