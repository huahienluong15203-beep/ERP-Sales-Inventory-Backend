package com.erp.backend.controller;

import com.erp.backend.dto.inventory.lot.*;
import com.erp.backend.dto.product.ProductResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.exception.GlobalExceptionHandler;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.ProductLotService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test ProductLotController - S6-01 Quản lý lô và hạn sử dụng")
class ProductLotControllerTest {

    @Mock
    private ProductLotService productLotService;

    @InjectMocks
    private ProductLotController productLotController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private UserDetailsImpl mockUserDetails;

    @BeforeEach
    void setUp() {
        mockUserDetails = new UserDetailsImpl(
                2L, "wh_mgr", "Quản lý kho", "manager@erp.com", "password", true, List.of()
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
                return mockUserDetails;
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(productLotController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(authPrincipalResolver)
                .build();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("GET /api/inventory/lots - Tra cứu danh sách lô hàng phân trang -> 200 OK")
    void searchLots_returnsOk() throws Exception {
        ProductLotResponse lot = ProductLotResponse.builder()
                .id(1L)
                .productId(20L)
                .productSku("SP-PEPSI")
                .productName("Pepsi")
                .warehouseId(10L)
                .batchNumber("LOT-01")
                .quantity(new BigDecimal("100"))
                .status("ACTIVE")
                .expiryStatus("SAFE")
                .build();

        PageResponse<ProductLotResponse> pageResponse = new PageResponse<>(List.of(lot), 0, 20, 1, 1);

        when(productLotService.searchLots(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(pageResponse);

        mockMvc.perform(get("/api/inventory/lots")
                        .param("warehouseId", "10")
                        .param("productId", "20")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].batchNumber").value("LOT-01"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/inventory/lots/{id} - Xem chi tiết lô hàng -> 200 OK")
    void getLotById_returnsOk() throws Exception {
        ProductLotResponse lot = ProductLotResponse.builder()
                .id(1L)
                .batchNumber("LOT-01")
                .productSku("SP-PEPSI")
                .status("ACTIVE")
                .build();

        when(productLotService.getLotById(eq(1L), any())).thenReturn(lot);

        mockMvc.perform(get("/api/inventory/lots/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.batchNumber").value("LOT-01"));
    }

    @Test
    @DisplayName("POST /api/inventory/lots - Khai báo lô hàng mới hợp lệ -> 201 Created")
    void createLot_success() throws Exception {
        ProductLotCreateRequest req = ProductLotCreateRequest.builder()
                .productId(20L)
                .warehouseId(10L)
                .batchNumber("LOT-NEW-01")
                .manufactureDate(LocalDate.now().minusDays(10))
                .expiredDate(LocalDate.now().plusMonths(6))
                .initialQuantity(new BigDecimal("50"))
                .build();

        ProductLotResponse response = ProductLotResponse.builder()
                .id(5L)
                .productId(20L)
                .warehouseId(10L)
                .batchNumber("LOT-NEW-01")
                .quantity(new BigDecimal("50"))
                .status("ACTIVE")
                .build();

        when(productLotService.createLot(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/inventory/lots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.batchNumber").value("LOT-NEW-01"));
    }

    @Test
    @DisplayName("POST /api/inventory/lots - Thiếu số lô (batchNumber) -> 400 Bad Request")
    void createLot_missingBatchNumber_returnsBadRequest() throws Exception {
        ProductLotCreateRequest req = ProductLotCreateRequest.builder()
                .productId(20L)
                .warehouseId(10L)
                .expiredDate(LocalDate.now().plusMonths(6))
                .build(); // batchNumber rỗng

        mockMvc.perform(post("/api/inventory/lots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/inventory/lots/{id} - Cập nhật thông tin lô hàng -> 200 OK")
    void updateLot_success() throws Exception {
        ProductLotUpdateRequest req = ProductLotUpdateRequest.builder()
                .status("LOCKED")
                .notes("Khóa lô")
                .build();

        ProductLotResponse response = ProductLotResponse.builder()
                .id(1L)
                .status("LOCKED")
                .notes("Khóa lô")
                .build();

        when(productLotService.updateLot(eq(1L), any(), any())).thenReturn(response);

        mockMvc.perform(put("/api/inventory/lots/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));
    }

    @Test
    @DisplayName("PATCH /api/inventory/lots/products/{productId}/batch-management - Bật/tắt quản lý lô theo SKU -> 200 OK")
    void toggleBatchManagement_success() throws Exception {
        ProductBatchManagementUpdateRequest req = ProductBatchManagementUpdateRequest.builder()
                .hasBatchManagement(true)
                .build();

        ProductResponse response = ProductResponse.builder()
                .id(20L)
                .sku("SP-PEPSI")
                .hasBatchManagement(true)
                .build();

        when(productLotService.toggleBatchManagement(eq(20L), eq(true), any())).thenReturn(response);

        mockMvc.perform(patch("/api/inventory/lots/products/20/batch-management")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasBatchManagement").value(true));
    }

    @Test
    @DisplayName("GET /api/inventory/lots/product-stock - Tính tồn chi tiết theo lô FEFO -> 200 OK")
    void calculateStockByLot_returnsOk() throws Exception {
        ProductLotStockResponse stockResponse = ProductLotStockResponse.builder()
                .productId(20L)
                .productSku("SP-PEPSI")
                .totalStock(new BigDecimal("100"))
                .validStock(new BigDecimal("80"))
                .nearExpiryStock(new BigDecimal("20"))
                .expiredStock(BigDecimal.ZERO)
                .build();

        when(productLotService.calculateStockByLot(eq(20L), eq(10L), anyInt(), any()))
                .thenReturn(stockResponse);

        mockMvc.perform(get("/api/inventory/lots/product-stock")
                        .param("productId", "20")
                        .param("warehouseId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(20))
                .andExpect(jsonPath("$.totalStock").value(100));
    }

    @Test
    @DisplayName("POST /api/inventory/lots/validate-dispatch - Kiểm tra HSD khi xuất kho -> 200 OK")
    void validateLotForDispatch_returnsOk() throws Exception {
        LotDispatchValidationRequest req = LotDispatchValidationRequest.builder()
                .productId(20L)
                .warehouseId(10L)
                .batchNumber("LOT-01")
                .quantity(new BigDecimal("10"))
                .build();

        LotDispatchValidationResponse response = LotDispatchValidationResponse.builder()
                .valid(true)
                .batchNumber("LOT-01")
                .message("Đủ điều kiện xuất kho")
                .build();

        when(productLotService.validateLotForDispatch(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/inventory/lots/validate-dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.message").value("Đủ điều kiện xuất kho"));
    }

    @Test
    @DisplayName("GET /api/inventory/lots/expiring - Cảnh báo danh sách lô sắp hết hạn -> 200 OK")
    void getExpiringLots_returnsOk() throws Exception {
        ProductLotResponse lot = ProductLotResponse.builder()
                .id(1L)
                .batchNumber("LOT-EXP-SOON")
                .expiryStatus("NEAR_EXPIRATION")
                .build();

        PageResponse<ProductLotResponse> page = new PageResponse<>(List.of(lot), 0, 20, 1, 1);
        when(productLotService.getExpiringLots(anyInt(), any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/inventory/lots/expiring")
                        .param("daysThreshold", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].batchNumber").value("LOT-EXP-SOON"));
    }

    @Test
    @DisplayName("GET /api/inventory/lots/summary - Thống kê tổng quan trạng thái lô -> 200 OK")
    void getLotSummary_returnsOk() throws Exception {
        LotStockSummaryResponse summary = LotStockSummaryResponse.builder()
                .totalLots(10)
                .totalQuantity(new BigDecimal("500"))
                .safeLots(7)
                .nearExpiryLots(2)
                .expiredLots(1)
                .build();

        when(productLotService.getLotSummary(anyInt(), any(), any())).thenReturn(summary);

        mockMvc.perform(get("/api/inventory/lots/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLots").value(10))
                .andExpect(jsonPath("$.safeLots").value(7));
    }

    @Test
    @DisplayName("GET /api/inventory/lots/fefo-suggestion - Gợi ý lô xuất FEFO tự động -> 200 OK")
    void getFefoLotSuggestion_returnsOk() throws Exception {
        FefoLotSuggestionResponse response = FefoLotSuggestionResponse.builder()
                .productId(20L)
                .warehouseId(10L)
                .requestedQuantity(new BigDecimal("30"))
                .suggestedBatchNumber("LOT-202610-A")
                .isSingleLotSufficient(true)
                .suggestedLot(FefoLotSuggestionResponse.SuggestedLotItem.builder()
                        .lotId(1L)
                        .batchNumber("LOT-202610-A")
                        .quantity(new BigDecimal("50"))
                        .availableQuantity(new BigDecimal("50"))
                        .isDefault(true)
                        .isSufficient(true)
                        .build())
                .build();

        when(productLotService.getFefoLotSuggestion(eq(20L), eq(10L), any(BigDecimal.class), any()))
                .thenReturn(response);

        mockMvc.perform(get("/api/inventory/lots/fefo-suggestion")
                        .param("productId", "20")
                        .param("warehouseId", "10")
                        .param("requestedQuantity", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedBatchNumber").value("LOT-202610-A"))
                .andExpect(jsonPath("$.isSingleLotSufficient").value(true));
    }

    @Test
    @DisplayName("POST /api/inventory/lots/validate-selection - Kiểm tra tính hợp lệ và lý do khi thủ kho chọn lô -> 200 OK")
    void validateLotSelection_returnsOk() throws Exception {
        LotSelectionValidationRequest req = LotSelectionValidationRequest.builder()
                .productId(20L)
                .warehouseId(10L)
                .selectedBatchNumber("LOT-202610-B")
                .requestedQuantity(new BigDecimal("20"))
                .lotChangeReason("Khách yêu cầu date mới")
                .build();

        LotSelectionValidationResponse response = LotSelectionValidationResponse.builder()
                .valid(true)
                .selectedBatchNumber("LOT-202610-B")
                .suggestedBatchNumber("LOT-202610-A")
                .isDifferentFromSuggested(true)
                .requiresReason(true)
                .reasonValid(true)
                .message("Lô hàng hợp lệ kèm lý do")
                .build();

        when(productLotService.validateLotSelection(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/inventory/lots/validate-selection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.requiresReason").value(true))
                .andExpect(jsonPath("$.reasonValid").value(true));
    }
}
