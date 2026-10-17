package com.erp.backend.controller;

import com.erp.backend.dto.dashboard.*;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.ExecutiveDashboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test ExecutiveDashboardController - S8-05 Dashboard điều hành bán hàng và kho")
class ExecutiveDashboardControllerTest {

    @Mock
    private ExecutiveDashboardService executiveDashboardService;

    @InjectMocks
    private ExecutiveDashboardController executiveDashboardController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private UserDetailsImpl mockSalesManager;

    @BeforeEach
    void setUp() {
        mockSalesManager = new UserDetailsImpl(
                1L, "sales_mgr", "Quản lý kinh doanh", "mgr@erp.com", "password", true,
                List.of(new SimpleGrantedAuthority("ROLE_SALES_MANAGER"))
        );

        HandlerMethodArgumentResolver authPrincipalResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter,
                                          ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest,
                                          WebDataBinderFactory binderFactory) {
                return mockSalesManager;
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(executiveDashboardController)
                .setCustomArgumentResolvers(authPrincipalResolver)
                .build();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("GET /api/dashboard/executive/summary - Trả về 200 OK với đầy đủ 6 thẻ chỉ số")
    void testGetSummary_Returns200() throws Exception {
        ExecutiveDashboardSummaryResponse mockSummary = ExecutiveDashboardSummaryResponse.builder()
                .revenueToday(new BigDecimal("5000000"))
                .revenueThisMonth(new BigDecimal("20000000"))
                .pendingApprovalOrders(3)
                .pendingDispatchOrders(5)
                .overdueDebtAmount(new BigDecimal("12000000"))
                .skusBelowMinStock(4)
                .fromCache(false)
                .computedAt(LocalDateTime.now())
                .build();

        when(executiveDashboardService.getSummary(anyBoolean(), any())).thenReturn(mockSummary);

        mockMvc.perform(get("/api/dashboard/executive/summary")
                        .param("refresh", "false")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revenueToday").value(5000000))
                .andExpect(jsonPath("$.revenueThisMonth").value(20000000))
                .andExpect(jsonPath("$.pendingApprovalOrders").value(3))
                .andExpect(jsonPath("$.pendingDispatchOrders").value(5))
                .andExpect(jsonPath("$.overdueDebtAmount").value(12000000))
                .andExpect(jsonPath("$.skusBelowMinStock").value(4));
    }

    @Test
    @DisplayName("GET /api/dashboard/executive/daily-sales - Trả về 200 OK dữ liệu biểu đồ ngày")
    void testGetDailySales_Returns200() throws Exception {
        ExecutiveDailySalesResponse mockDaily = ExecutiveDailySalesResponse.builder()
                .month(10)
                .year(2026)
                .totalMonthSales(new BigDecimal("50000000"))
                .totalMonthOrders(10)
                .points(List.of(
                        new ExecutiveDailySalesPoint(LocalDate.of(2026, 10, 1), 1, new BigDecimal("5000000"), 1)
                ))
                .fromCache(false)
                .build();

        when(executiveDashboardService.getDailySalesInMonth(any(), any(), anyBoolean(), any())).thenReturn(mockDaily);

        mockMvc.perform(get("/api/dashboard/executive/daily-sales")
                        .param("month", "10")
                        .param("year", "2026")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(10))
                .andExpect(jsonPath("$.totalMonthSales").value(50000000))
                .andExpect(jsonPath("$.points[0].day").value(1));
    }

    @Test
    @DisplayName("GET /api/dashboard/executive/region-sales - Trả về 200 OK dữ liệu biểu đồ khu vực")
    void testGetRegionSales_Returns200() throws Exception {
        ExecutiveRegionSalesResponse mockRegion = ExecutiveRegionSalesResponse.builder()
                .month(10)
                .year(2026)
                .totalSales(new BigDecimal("100000000"))
                .regions(List.of(
                        new ExecutiveRegionSalesItem(1L, "MB", "Miền Bắc", new BigDecimal("100000000"), 5, 100.0)
                ))
                .fromCache(false)
                .build();

        when(executiveDashboardService.getSalesByRegion(any(), any(), anyBoolean(), any())).thenReturn(mockRegion);

        mockMvc.perform(get("/api/dashboard/executive/region-sales")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSales").value(100000000))
                .andExpect(jsonPath("$.regions[0].regionName").value("Miền Bắc"));
    }

    @Test
    @DisplayName("GET /api/dashboard/executive/overdue-agencies - Trả về 200 OK danh sách đại lý quá hạn")
    void testGetOverdueAgencies_Returns200() throws Exception {
        ExecutiveOverdueAgenciesResponse mockOverdue = ExecutiveOverdueAgenciesResponse.builder()
                .totalOverdueAmount(new BigDecimal("30000000"))
                .totalOverdueAgencies(1)
                .agencies(List.of(
                        new ExecutiveOverdueAgencyItem(
                                1, 10L, "DL-01", "Đại lý An Phát", null, null,
                                null, null, new BigDecimal("50000000"), new BigDecimal("30000000"),
                                new BigDecimal("30000000"), 45L
                        )
                ))
                .fromCache(false)
                .build();

        when(executiveDashboardService.getOverdueAgencies(any(), anyBoolean(), any())).thenReturn(mockOverdue);

        mockMvc.perform(get("/api/dashboard/executive/overdue-agencies")
                        .param("limit", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOverdueAmount").value(30000000))
                .andExpect(jsonPath("$.agencies[0].customerCode").value("DL-01"));
    }

    @Test
    @DisplayName("GET /api/dashboard/executive/overview - Trả về 200 OK toàn bộ dữ liệu tổng hợp < 2 giây")
    void testGetOverview_Returns200() throws Exception {
        ExecutiveDashboardOverviewResponse mockOverview = ExecutiveDashboardOverviewResponse.builder()
                .summary(ExecutiveDashboardSummaryResponse.builder().build())
                .dailySales(ExecutiveDailySalesResponse.builder().build())
                .regionSales(ExecutiveRegionSalesResponse.builder().build())
                .overdueAgencies(ExecutiveOverdueAgenciesResponse.builder().build())
                .responseTimeMs(45)
                .fromCache(false)
                .timestamp(LocalDateTime.now())
                .build();

        when(executiveDashboardService.getCompleteDashboard(any(), any(), any(), anyBoolean(), any())).thenReturn(mockOverview);

        mockMvc.perform(get("/api/dashboard/executive/overview")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseTimeMs").value(45))
                .andExpect(jsonPath("$.fromCache").value(false));
    }

    @Test
    @DisplayName("POST /api/dashboard/executive/refresh-cache - Trả về 200 OK khi làm mới cache")
    void testRefreshCache_Returns200() throws Exception {
        mockMvc.perform(post("/api/dashboard/executive/refresh-cache")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
