package com.erp.backend.dto.debt;

import java.time.LocalDate;

/** S7-06: Lập biên bản đối chiếu. statementDate trống = hôm nay; draft = true thì lưu nháp, chưa gửi đại lý. */
public record DebtReconciliationCreateRequest(
        Long customerId,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate statementDate,
        String notes,
        Boolean draft) {
}
