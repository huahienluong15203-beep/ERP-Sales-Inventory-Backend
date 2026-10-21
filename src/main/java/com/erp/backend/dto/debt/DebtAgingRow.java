package com.erp.backend.dto.debt;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S7-03: Một dòng sổ công nợ theo đại lý.
 * - receivable = phải thu (tổng hoá đơn + đơn đã duyệt chưa xuất hoá đơn), collected = đã thu, outstanding = còn nợ.
 * - d0To30 ... over90: số còn nợ chia theo tuổi nợ (số ngày từ ngày hoá đơn / ngày duyệt đơn đến hôm nay).
 * - overdueAmount: phần còn nợ đã quá số ngày nợ tối đa của đại lý; oldestDebtDays: tuổi khoản còn nợ lâu nhất.
 * - creditBalance: giảm trừ do trả hàng chưa đối trừ được vào hoá đơn (S8-02), đã trừ vào outstanding,
 *   các nhóm tuổi nợ và nợ quá hạn (trừ vào khoản nợ cũ nhất trước).
 * - latestActivity: thời điểm phát sinh chứng từ / đơn hàng gần nhất của đại lý (phục vụ sắp xếp mới nhất).
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
        long oldestDebtDays,
        BigDecimal creditBalance,
        LocalDateTime latestActivity) {

    public DebtAgingRow(Long customerId, String customerCode, String customerName, Long salesRepId, String salesRepName,
                        BigDecimal creditLimit, Integer maxDebtDays, BigDecimal receivable, BigDecimal collected,
                        BigDecimal outstanding, BigDecimal uninvoiced, BigDecimal d0To30, BigDecimal d31To60,
                        BigDecimal d61To90, BigDecimal over90, BigDecimal overdueAmount, long oldestDebtDays,
                        BigDecimal creditBalance) {
        this(customerId, customerCode, customerName, salesRepId, salesRepName, creditLimit, maxDebtDays, receivable,
                collected, outstanding, uninvoiced, d0To30, d31To60, d61To90, over90, overdueAmount, oldestDebtDays,
                creditBalance, null);
    }

    /** Dòng không có giảm trừ chưa đối trừ. */
    public DebtAgingRow(Long customerId, String customerCode, String customerName, Long salesRepId, String salesRepName,
                        BigDecimal creditLimit, Integer maxDebtDays, BigDecimal receivable, BigDecimal collected,
                        BigDecimal outstanding, BigDecimal uninvoiced, BigDecimal d0To30, BigDecimal d31To60,
                        BigDecimal d61To90, BigDecimal over90, BigDecimal overdueAmount, long oldestDebtDays) {
        this(customerId, customerCode, customerName, salesRepId, salesRepName, creditLimit, maxDebtDays, receivable,
                collected, outstanding, uninvoiced, d0To30, d31To60, d61To90, over90, overdueAmount, oldestDebtDays,
                BigDecimal.ZERO.setScale(2), null);
    }
}
