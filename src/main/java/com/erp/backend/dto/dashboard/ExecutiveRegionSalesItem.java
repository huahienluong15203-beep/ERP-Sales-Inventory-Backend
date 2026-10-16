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
public class ExecutiveRegionSalesItem {
    private Long regionId;
    private String regionCode;
    private String regionName;
    private BigDecimal amount;
    private long orderCount;
    private double percentage;
}
