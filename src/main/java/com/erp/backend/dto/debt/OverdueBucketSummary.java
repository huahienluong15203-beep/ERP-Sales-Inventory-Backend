package com.erp.backend.dto.debt;

import java.math.BigDecimal;

/** S8-09: Tổng hợp theo nhóm tuổi nợ (trên các đại lý có nợ quá hạn). */
public record OverdueBucketSummary(String bucket, String label, int customerCount, BigDecimal amount) {
}
