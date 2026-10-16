package com.erp.backend.dto.debt;

import java.math.BigDecimal;

/** S7-03: Dòng tổng cộng ở chân sổ công nợ (cộng trên mọi dòng đã lọc, không chỉ trang đang xem). */
public record DebtAgingTotals(
        int customerCount,
        BigDecimal receivable,
        BigDecimal collected,
        BigDecimal outstanding,
        BigDecimal uninvoiced,
        BigDecimal d0To30,
        BigDecimal d31To60,
        BigDecimal d61To90,
        BigDecimal over90,
        BigDecimal overdueAmount) {
}
