package com.erp.backend.service;

import com.erp.backend.dto.audit.AuditLogResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogCostMaskTest {

    private static AuditLogResponse log(String action) {
        return new AuditLogResponse(1L, "PRICING", "Giá", action, "PRODUCT", 9L, "SP-1", 2L, "sales_manager",
                "QLKD", null, "Quản lý kinh doanh", "10000", "12000", "Cập nhật giá vốn 10000 -> 12000", null, null, null, null,
                LocalDateTime.now());
    }

    @Test
    @DisplayName("S1-05: QL kho / Kế toán xem nhật ký giá vốn -> giá trị trước / sau bị che")
    void costLog_maskedForOtherRoles() {
        AuditLogResponse r = AuditLogService.maskCostIfNeeded(log("UPDATE_COST_PRICE"), false);

        assertThat(r.oldValue()).isEqualTo(AuditLogService.HIDDEN_VALUE);
        assertThat(r.newValue()).isEqualTo(AuditLogService.HIDDEN_VALUE);
        assertThat(r.reason()).doesNotContain("10000");
    }

    @Test
    @DisplayName("S1-05: QL kinh doanh xem được; nhật ký không phải giá vốn thì không che")
    void costLog_visibleForManager_andOtherLogsUntouched() {
        assertThat(AuditLogService.maskCostIfNeeded(log("UPDATE_COST_PRICE"), true).oldValue()).isEqualTo("10000");
        assertThat(AuditLogService.maskCostIfNeeded(log("UPDATE_PRICE_LIST"), false).oldValue()).isEqualTo("10000");
    }
}
