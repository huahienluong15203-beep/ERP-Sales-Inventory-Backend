package com.erp.backend.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutiveOverdueAgencyItem {
    private int rank;
    private Long customerId;
    private String customerCode;
    private String customerName;
    private String contactName;
    private String phone;
    private Long salesRepId;
    private String salesRepName;
    private BigDecimal creditLimit;
    private BigDecimal currentDebt;
    private BigDecimal overdueAmount;
    private long oldestOverdueDays;
}
