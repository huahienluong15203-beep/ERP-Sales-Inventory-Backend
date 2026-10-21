package com.erp.backend.controller;

import com.erp.backend.dto.inventory.aging.*;
import com.erp.backend.service.InventoryAgingReportService;
import com.erp.backend.security.UserDetailsImpl;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test InventoryAgingReportController - S8-07 Báo cáo tồn kho và vòng quay hàng")
class InventoryAgingReportControllerTest {

    @Mock
    private InventoryAgingReportService agingReportService;

    @InjectMocks
    private InventoryAgingReportController agingReportController;

    private MockMvc mockMvc;
    private UserDetailsImpl mockActor;

    @BeforeEach
    void setUp() {
        mockActor = new UserDetailsImpl(
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
                return mockActor;
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(agingReportController)
                .setCustomArgumentResolvers(authPrincipalResolver)
                .build();
    }

    @Test
    @DisplayName("GET /api/inventory/aging-report -> 200 OK trả về báo cáo tồn kho và tuổi hàng")
    void testGetAgingReport_Returns200() throws Exception {
        InventoryAgingReportResponse mockResponse = InventoryAgingReportResponse.builder()
                .items(List.of(
                        InventoryAgingItemResponse.builder()
                                .productSku("BIA-HN-01")
                                .productName("Bia Hà Nội")
                                .quantity(new BigDecimal("100"))
                                .costPrice(new BigDecimal("12000"))
                                .totalValue(new BigDecimal("1200000"))
                                .averageAgingDays(25)
                                .agingBracket("0_30")
                                .turnoverSpeed("FAST")
                                .build()
                ))
                .summary(InventoryAgingSummaryResponse.builder()
                        .totalInventoryValue(new BigDecimal("1200000"))
                        .slowMovingCount(0)
                        .slowMovingRatio(0.0)
                        .stagnantValue(BigDecimal.ZERO)
                        .averageSystemDays(25.0)
                        .build())
                .build();

        when(agingReportService.getAgingReport(any(), any(), any(), any(), any()))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/inventory/aging-report")
                        .param("warehouseId", "ALL")
                        .param("categoryId", "ALL")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productSku").value("BIA-HN-01"))
                .andExpect(jsonPath("$.summary.totalInventoryValue").value(1200000));
    }

    @Test
    @DisplayName("GET /api/inventory/aging-report/export -> 200 OK trả về file Excel")
    void testExportExcel_Returns200WithExcelContent() throws Exception {
        byte[] mockBytes = "fake-excel-content".getBytes();

        when(agingReportService.exportToExcel(any(), any(), any(), any(), any()))
                .thenReturn(mockBytes);

        mockMvc.perform(get("/api/inventory/aging-report/export")
                        .param("warehouseId", "ALL"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".xlsx")))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    @DisplayName("GET /api/inventory/aging-report/summary-by-warehouse -> 200 OK thống kê theo kho")
    void testGetSummaryByWarehouse_Returns200() throws Exception {
        when(agingReportService.getSummaryByWarehouse(any()))
                .thenReturn(List.of(
                        InventoryAgingWarehouseValueResponse.builder()
                                .warehouseId(1L)
                                .warehouseCode("WH-MB01")
                                .skuCount(10)
                                .totalValue(new BigDecimal("50000000"))
                                .build()
                ));

        mockMvc.perform(get("/api/inventory/aging-report/summary-by-warehouse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].warehouseCode").value("WH-MB01"));
    }

    @Test
    @DisplayName("GET /api/inventory/aging-report/summary-by-category -> 200 OK thống kê theo nhóm hàng")
    void testGetSummaryByCategory_Returns200() throws Exception {
        when(agingReportService.getSummaryByCategory(any()))
                .thenReturn(List.of(
                        InventoryAgingCategoryValueResponse.builder()
                                .categoryId(10L)
                                .categoryCode("DO_UONG")
                                .skuCount(5)
                                .totalValue(new BigDecimal("30000000"))
                                .build()
                ));

        mockMvc.perform(get("/api/inventory/aging-report/summary-by-category"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].categoryCode").value("DO_UONG"));
    }
}
