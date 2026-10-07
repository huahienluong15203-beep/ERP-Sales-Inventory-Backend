package com.erp.backend.controller;

import com.erp.backend.dto.product.CreateProductUnitConversionRequest;
import com.erp.backend.dto.product.ProductUnitConversionResponse;
import com.erp.backend.dto.product.UnitConversionCalculateRequest;
import com.erp.backend.dto.product.UnitConversionResult;
import com.erp.backend.dto.product.UpdateProductUnitConversionRequest;
import com.erp.backend.entity.UnitConversionSnapshot;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.ProductUnitConversionService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test ProductUnitConversionController - S2-07 Đơn vị tính quy đổi")
class ProductUnitConversionControllerTest {

    @Mock
    private ProductUnitConversionService unitConversionService;

    @InjectMocks
    private ProductUnitConversionController unitConversionController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private UserDetailsImpl mockUserDetails;

    @BeforeEach
    void setUp() {
        mockUserDetails = new UserDetailsImpl(
                99L, "warehouse_staff", "Nguyễn Văn Kho", "wh@erp.com", "password", true, List.of()
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

        mockMvc = MockMvcBuilders.standaloneSetup(unitConversionController)
                .setCustomArgumentResolvers(authPrincipalResolver)
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("S2-07: Lấy danh sách đơn vị tính của SKU -> 200 OK")
    void getUnits_Success() throws Exception {
        ProductUnitConversionResponse baseUnit = ProductUnitConversionResponse.builder()
                .unitName("Lon")
                .conversionFactor(BigDecimal.ONE)
                .isBaseUnit(true)
                .build();

        ProductUnitConversionResponse boxUnit = ProductUnitConversionResponse.builder()
                .id(1L)
                .unitName("Thùng")
                .conversionFactor(BigDecimal.valueOf(24))
                .isBaseUnit(false)
                .build();

        when(unitConversionService.getUnitConversions(10L)).thenReturn(List.of(baseUnit, boxUnit));

        mockMvc.perform(get("/api/products/10/units"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].unitName").value("Lon"))
                .andExpect(jsonPath("$[0].baseUnit").value(true))
                .andExpect(jsonPath("$[1].unitName").value("Thùng"))
                .andExpect(jsonPath("$[1].conversionFactor").value(24));
    }

    @Test
    @DisplayName("S2-07 AC1: Khai báo thêm đơn vị quy đổi mới (Thùng hệ số 24) -> 201 Created")
    void addUnit_Success() throws Exception {
        CreateProductUnitConversionRequest request = CreateProductUnitConversionRequest.builder()
                .unitName("Thùng")
                .conversionFactor(BigDecimal.valueOf(24))
                .description("Thùng 24 lon")
                .build();

        ProductUnitConversionResponse response = ProductUnitConversionResponse.builder()
                .id(100L)
                .productId(10L)
                .unitName("Thùng")
                .conversionFactor(BigDecimal.valueOf(24))
                .isBaseUnit(false)
                .formula("1 Thùng = 24 Lon")
                .status("ACTIVE")
                .build();

        when(unitConversionService.addUnitConversion(eq(10L), any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/products/10/units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.unitName").value("Thùng"))
                .andExpect(jsonPath("$.conversionFactor").value(24))
                .andExpect(jsonPath("$.formula").value("1 Thùng = 24 Lon"));
    }

    @Test
    @DisplayName("S2-07 AC3: Cập nhật hệ số quy đổi -> 200 OK")
    void updateUnit_Success() throws Exception {
        UpdateProductUnitConversionRequest request = UpdateProductUnitConversionRequest.builder()
                .conversionFactor(BigDecimal.valueOf(20))
                .changeReason("Thay đổi quy cách đóng gói")
                .build();

        ProductUnitConversionResponse response = ProductUnitConversionResponse.builder()
                .id(100L)
                .productId(10L)
                .unitName("Thùng")
                .conversionFactor(BigDecimal.valueOf(20))
                .isBaseUnit(false)
                .build();

        when(unitConversionService.updateUnitConversion(eq(10L), eq(100L), any(), any())).thenReturn(response);

        mockMvc.perform(put("/api/products/10/units/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.conversionFactor").value(20));
    }

    @Test
    @DisplayName("S2-07: Xoá đơn vị quy đổi -> 204 No Content")
    void deleteUnit_Success() throws Exception {
        doNothing().when(unitConversionService).deleteUnitConversion(eq(10L), eq(100L), any());

        mockMvc.perform(delete("/api/products/10/units/100"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("S2-07 AC2: Tiện ích tính toán quy đổi đơn vị tính -> 200 OK")
    void calculateConversion_Success() throws Exception {
        UnitConversionCalculateRequest request = UnitConversionCalculateRequest.builder()
                .productId(10L)
                .unitName("Thùng")
                .quantity(BigDecimal.valueOf(10))
                .build();

        UnitConversionSnapshot snapshot = UnitConversionSnapshot.builder()
                .transactionUnit("Thùng")
                .transactionQuantity(BigDecimal.valueOf(10))
                .conversionFactor(BigDecimal.valueOf(24))
                .baseUnit("Lon")
                .baseQuantity(BigDecimal.valueOf(240))
                .build();

        UnitConversionResult result = UnitConversionResult.builder()
                .productId(10L)
                .sku("SP-COCA-330")
                .productName("Coca-Cola 330ml")
                .inputUnit("Thùng")
                .inputQuantity(BigDecimal.valueOf(10))
                .conversionFactor(BigDecimal.valueOf(24))
                .baseUnit("Lon")
                .baseQuantity(BigDecimal.valueOf(240))
                .formula("10 Thùng x 24 = 240 Lon")
                .convertedAt(LocalDateTime.now())
                .snapshot(snapshot)
                .build();

        when(unitConversionService.calculateConversion(any())).thenReturn(result);

        mockMvc.perform(post("/api/products/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputUnit").value("Thùng"))
                .andExpect(jsonPath("$.inputQuantity").value(10))
                .andExpect(jsonPath("$.conversionFactor").value(24))
                .andExpect(jsonPath("$.baseUnit").value("Lon"))
                .andExpect(jsonPath("$.baseQuantity").value(240))
                .andExpect(jsonPath("$.snapshot.baseQuantity").value(240));
    }
}
