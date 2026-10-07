package com.erp.backend.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("S2-04: Chưa đăng nhập truy cập /api/audit-logs -> 401 Unauthorized")
    void search_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S2-04: Vai trò không đủ quyền (ROLE_WAREHOUSE) xem audit log -> 403 Forbidden")
    void search_forbiddenRole_returns403() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("wh").roles("WAREHOUSE")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("S2-04: Kế toán (ROLE_ACCOUNTANT) xem audit log -> 200 OK")
    @WithMockUser(roles = "ACCOUNTANT")
    void search_accountant_returns200() throws Exception {
        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("S2-04: Quản trị viên (ROLE_ADMIN) xem danh sách modules hỗ trợ ghi log -> 200 OK")
    @WithMockUser(roles = "ADMIN")
    void getSupportedModules_admin_returns200() throws Exception {
        mockMvc.perform(get("/api/audit-logs/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.code == 'DEBT_LIMIT')].label").exists())
                .andExpect(jsonPath("$[?(@.code == 'INVENTORY')].label").exists())
                .andExpect(jsonPath("$[?(@.code == 'PRICING')].label").exists())
                .andExpect(jsonPath("$[?(@.code == 'INVOICE')].label").exists());
    }
}
