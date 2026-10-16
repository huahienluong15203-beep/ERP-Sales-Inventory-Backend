package com.erp.backend.dto.debt;

import java.math.BigDecimal;

/**
 * S7-03: Một dòng sổ công nợ theo đại lý.
 * - receivable = phải thu (tổng hoá đơn + đơn đã duyệt chưa xuất hoá đơn), collected = đã thu, outstanding = còn nợ.
 * - d0To30 ... over90: số còn nợ chia theo tuổi nợ (số ngày từ ngày hoá đơn / ngày duyệt đơn đến hôm nay).
 * - overdueAmount: phần còn nợ đã quá số ngày nợ tối đa của đại lý; oldestDebtDays: tuổi khoản còn nợ lâu nhất.
 */
public record DebtAgingRow(
        Long customerId,
        String customerCode,
        String customerName,
        Long salesRepId,
        String salesRepName,
        BigDecimal creditLimit,
        Integer maxDebtDays,
        BigDecimal receivable,
        BigDecimal collected,
        BigDecimal outstanding,
        BigDecimal uninvoiced,
        BigDecimal d0To30,
        BigDecimal d31To60,
        BigDecimal d61To90,
        BigDecimal over90,
        BigDecimal overdueAmount,
        long oldestDebtDays) {
}
