package com.erp.backend.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CustomerRiskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("S3-05: Chưa đăng nhập gọi PUT /api/customers/1/debt-limit -> 401 Unauthorized")
    void debtLimit_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/customers/1/debt-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creditLimit\":100000000,\"maxDebtDays\":30,\"reason\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S3-05: Vai trò Kho (WAREHOUSE) đổi hạn mức công nợ -> 403 Forbidden")
    @WithMockUser(roles = "WAREHOUSE")
    void debtLimit_warehouse_returns403() throws Exception {
        mockMvc.perform(put("/api/customers/1/debt-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creditLimit\":100000000,\"maxDebtDays\":30,\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("S3-05: Thiếu lý do thay đổi hạn mức công nợ -> 400 Bad Request")
    @WithMockUser(roles = "ACCOUNTANT")
    void debtLimit_missingReason_returns400() throws Exception {
        mockMvc.perform(put("/api/customers/1/debt-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creditLimit\":100000000,\"maxDebtDays\":30,\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("S3-05: Hạn mức công nợ âm -> 400 Bad Request")
    @WithMockUser(roles = "ACCOUNTANT")
    void debtLimit_negativeLimit_returns400() throws Exception {
        mockMvc.perform(put("/api/customers/1/debt-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creditLimit\":-5000,\"maxDebtDays\":30,\"reason\":\"test\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("S3-07: Chưa đăng nhập gọi PATCH /api/customers/1/transaction-lock -> 401 Unauthorized")
    void transactionLock_unauthenticated_returns401() throws Exception {
        mockMvc.perform(patch("/api/customers/1/transaction-lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locked\":true,\"reason\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S3-07: Vai trò Khách hàng (CUSTOMER) khóa giao dịch đại lý -> 403 Forbidden")
    @WithMockUser(roles = "CUSTOMER")
    void transactionLock_customerRole_returns403() throws Exception {
        mockMvc.perform(patch("/api/customers/1/transaction-lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locked\":true,\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("S3-07: Thiếu lý do khi khóa giao dịch đại lý -> 400 Bad Request")
    @WithMockUser(roles = "SALES_MANAGER")
    void transactionLock_missingReason_returns400() throws Exception {
        mockMvc.perform(patch("/api/customers/1/transaction-lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locked\":true,\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("S3-07 & S4-02: Chưa đăng nhập kiểm tra điều kiện tạo đơn -> 401 Unauthorized")
    void checkOrderCreation_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/customers/1/check-order-creation"))
                .andExpect(status().isUnauthorized());
    }
}
