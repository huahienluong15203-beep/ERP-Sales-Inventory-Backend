package com.erp.backend.controller;

import com.erp.backend.dto.product.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.ProductExcelImportService;
import com.erp.backend.service.ProductService;
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
import org.springframework.mock.web.MockMultipartFile;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test ProductManagementController - S2-05, S2-07, S2-08 Quản lý sản phẩm")
class ProductManagementControllerTest {

    @Mock
    private ProductExcelImportService productExcelImportService;

    @Mock
    private ProductService productService;

    @InjectMocks
    private ProductManagementController productManagementController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private UserDetailsImpl mockUserDetails;

    @BeforeEach
    void setUp() {
        mockUserDetails = new UserDetailsImpl(
                1L, "admin", "Quản trị viên", "admin@erp.com", "password", true, List.of()
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

        mockMvc = MockMvcBuilders.standaloneSetup(productManagementController)
                .setCustomArgumentResolvers(authPrincipalResolver)
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("S2-05, S2-07: Tạo sản phẩm mới kèm đơn vị quy đổi -> 201 Created")
    void createProduct_Success() throws Exception {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("SP-COCA-330")
                .name("Nước ngọt Coca-Cola 330ml")
                .baseUnit("Lon")
                .unitConversions(List.of(
                        CreateProductUnitConversionRequest.builder()
                                .unitName("Thùng")
                                .conversionFactor(BigDecimal.valueOf(24))
                                .build()
                ))
                .build();

        ProductDetailResponse response = ProductDetailResponse.builder()
                .id(1L)
                .sku("SP-COCA-330")
                .name("Nước ngọt Coca-Cola 330ml")
                .baseUnit("Lon")
                .unitConversions(List.of(
                        ProductUnitConversionResponse.builder()
                                .id(10L)
                                .unitName("Thùng")
                                .conversionFactor(BigDecimal.valueOf(24))
                                .isBaseUnit(false)
                                .build()
                ))
                .build();

        when(productService.createProduct(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.sku").value("SP-COCA-330"))
                .andExpect(jsonPath("$.unitConversions[0].unitName").value("Thùng"))
                .andExpect(jsonPath("$.unitConversions[0].conversionFactor").value(24));
    }

    @Test
    @DisplayName("S2-05, S2-07: Lấy chi tiết sản phẩm theo ID -> 200 OK")
    void getProductById_Success() throws Exception {
        ProductDetailResponse response = ProductDetailResponse.builder()
                .id(1L)
                .sku("SP-COCA-330")
                .name("Nước ngọt Coca-Cola 330ml")
                .baseUnit("Lon")
                .build();

        when(productService.getProductById(eq(1L), any())).thenReturn(response);

        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.sku").value("SP-COCA-330"));
    }

    @Test
    @DisplayName("S2-05: Lấy chi tiết sản phẩm theo SKU -> 200 OK")
    void getProductBySku_Success() throws Exception {
        ProductDetailResponse response = ProductDetailResponse.builder()
                .id(1L)
                .sku("SP-COCA-330")
                .name("Nước ngọt Coca-Cola 330ml")
                .baseUnit("Lon")
                .build();

        when(productService.getProductBySku(eq("SP-COCA-330"), any())).thenReturn(response);

        mockMvc.perform(get("/api/products/sku/SP-COCA-330"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("SP-COCA-330"));
    }

    @Test
    @DisplayName("S2-05: Danh sách sản phẩm phân trang -> 200 OK")
    void searchProducts_Success() throws Exception {
        ProductResponse pr = ProductResponse.builder()
                .id(1L)
                .sku("SP-COCA-330")
                .name("Coca-Cola")
                .baseUnit("Lon")
                .build();

        PageResponse<ProductResponse> pageResponse = new PageResponse<>(List.of(pr), 0, 20, 1, 1);
        when(productService.searchProducts(any(), any(), any(), any(), any())).thenReturn(pageResponse);

        mockMvc.perform(get("/api/products?keyword=coca&page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SP-COCA-330"));
    }

    @Test
    @DisplayName("S2-08 AC1: Tải tệp mẫu Excel sản phẩm -> 200 OK kèm header attachment")
    void downloadTemplate_Success() throws Exception {
        byte[] fakeBytes = new byte[]{1, 2, 3, 4};
        when(productExcelImportService.generateTemplate()).thenReturn(fakeBytes);

        mockMvc.perform(get("/api/products/import/template"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=Mau_Nhap_SanPham_ERP.xlsx"))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(fakeBytes));
    }

    @Test
    @DisplayName("S2-08 AC1: Xem trước dữ liệu tệp Excel sản phẩm -> 200 OK")
    void previewImport_Success() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "san_pham.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});

        ProductImportPreviewResponse response = ProductImportPreviewResponse.builder()
                .totalRows(2)
                .validRows(2)
                .invalidRows(0)
                .createCount(1)
                .updateCount(1)
                .rows(List.of())
                .build();

        when(productExcelImportService.previewImport(any())).thenReturn(response);

        mockMvc.perform(multipart("/api/products/import/preview").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.createCount").value(1))
                .andExpect(jsonPath("$.updateCount").value(1));
    }

    @Test
    @DisplayName("S2-08: Thực thi nhập danh mục sản phẩm từ tệp Excel -> 200 OK")
    void executeImport_Success() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "san_pham.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});

        ProductImportSummaryResponse summary = ProductImportSummaryResponse.builder()
                .totalRows(2)
                .successCount(2)
                .createdCount(1)
                .updatedCount(1)
                .errorCount(0)
                .importedAt(LocalDateTime.now())
                .message("Nhập thành công")
                .build();

        when(productExcelImportService.executeImport(any())).thenReturn(summary);

        mockMvc.perform(multipart("/api/products/import/execute").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.errorCount").value(0));
    }
}
