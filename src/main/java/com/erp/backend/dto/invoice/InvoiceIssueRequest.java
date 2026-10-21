package com.erp.backend.dto.invoice;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * S7-01: Lập (phát hành) hoá đơn cho 1 đại lý.
 * - orderIds: các đơn đã giao muốn gộp vào 1 hoá đơn; bỏ trống = mọi đơn đã giao chưa lập hoá đơn trong [fromDate, toDate].
 * - invoiceDate: ngày hoá đơn (bỏ trống = hôm nay), không ở tương lai, không trước ngày của hoá đơn gần nhất cùng năm.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceIssueRequest {

    @NotNull(message = "Vui lòng chọn đại lý")
    private Long customerId;

    @Size(max = 200, message = "Một hoá đơn gộp tối đa 200 đơn hàng")
    private List<Long> orderIds;

    private LocalDate fromDate;

    private LocalDate toDate;

    private LocalDate invoiceDate;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String note;
}
