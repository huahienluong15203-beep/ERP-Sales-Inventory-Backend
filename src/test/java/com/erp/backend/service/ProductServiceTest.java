package com.erp.backend.service;

import com.erp.backend.dto.product.*;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.Product;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.ProductRepository;
import com.erp.backend.repository.ProductUnitConversionRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test ProductService - S2-05 & S2-07 Quản lý danh mục & Khai báo SKU")
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductUnitConversionRepository unitConversionRepository;

    @Mock
    private ProductUnitConversionService unitConversionService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ProductService productService;

    private UserDetailsImpl actor;

    @BeforeEach
    void setUp() {
        actor = new UserDetailsImpl(1L, "admin", "Quản trị viên", "admin@erp.com", "password", true,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    @DisplayName("S2-05, S2-07: Tạo sản phẩm mới kèm mã SKU duy nhất và nhiều đơn vị quy đổi thành công")
    void createProduct_Success_WithUnitConversions() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("SP-COCA-330")
                .name("Nước ngọt Coca-Cola 330ml")
                .category("Nước giải khát")
                .baseUnit("Lon")
                .packaging("Thùng 24 lon")
                .costPrice(BigDecimal.valueOf(210000))
                .unitConversions(List.of(
                        CreateProductUnitConversionRequest.builder()
                                .unitName("Lốc")
                                .conversionFactor(BigDecimal.valueOf(6))
                                .description("Lốc 6 lon")
                                .build(),
                        CreateProductUnitConversionRequest.builder()
                                .unitName("Thùng")
                                .conversionFactor(BigDecimal.valueOf(24))
                                .description("Thùng 24 lon")
                                .build()
                ))
                .build();

        when(productRepository.existsBySku("SP-COCA-330")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(10L);
            return p;
        });

        ProductUnitConversionResponse baseUnit = ProductUnitConversionResponse.builder()
                .unitName("Lon")
                .conversionFactor(BigDecimal.ONE)
                .isBaseUnit(true)
                .build();

        ProductUnitConversionResponse boxUnit = ProductUnitConversionResponse.builder()
                .id(101L)
                .unitName("Thùng")
                .conversionFactor(BigDecimal.valueOf(24))
                .isBaseUnit(false)
                .build();

        when(unitConversionService.buildAllUnitsList(any(Product.class))).thenReturn(List.of(baseUnit, boxUnit));

        ProductDetailResponse response = productService.createProduct(request, actor);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getSku()).isEqualTo("SP-COCA-330");
        assertThat(response.getBaseUnit()).isEqualTo("Lon");
        assertThat(response.getAllUnits()).hasSize(2);

        // Kiểm tra audit log
        verify(auditLogService).record(
                eq(AuditModule.INVENTORY),
                eq("CREATE_PRODUCT"),
                eq("PRODUCT"),
                eq(10L),
                eq("SP-COCA-330"),
                isNull(),
                anyString(),
                anyString(),
                eq(actor)
        );
    }

    @Test
    @DisplayName("S2-05: Tạo sản phẩm trùng mã SKU -> throw conflict")
    void createProduct_Fails_WhenDuplicateSku() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("SP-COCA-330")
                .name("Nước ngọt Coca-Cola 330ml")
                .baseUnit("Lon")
                .build();

        when(productRepository.existsBySku("SP-COCA-330")).thenReturn(true);

        assertThatThrownBy(() -> productService.createProduct(request, actor))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_SKU")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("S2-07: Khai báo đơn vị quy đổi ban đầu trùng tên với đơn vị cơ sở -> throw bad request")
    void createProduct_Fails_WhenUnitNameEqualsBaseUnit() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("SP-COCA-330")
                .name("Nước ngọt Coca-Cola 330ml")
                .baseUnit("Lon")
                .unitConversions(List.of(
                        CreateProductUnitConversionRequest.builder()
                                .unitName("Lon") // Trùng baseUnit
                                .conversionFactor(BigDecimal.valueOf(1))
                                .build()
                ))
                .build();

        when(productRepository.existsBySku("SP-COCA-330")).thenReturn(false);

        assertThatThrownBy(() -> productService.createProduct(request, actor))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "UNIT_EQUALS_BASE_UNIT");
    }

    @Test
    @DisplayName("S2-07: Khai báo danh sách đơn vị quy đổi có 2 đơn vị trùng tên nhau -> throw bad request")
    void createProduct_Fails_WhenDuplicateUnitInRequest() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("SP-COCA-330")
                .name("Nước ngọt Coca-Cola 330ml")
                .baseUnit("Lon")
                .unitConversions(List.of(
                        CreateProductUnitConversionRequest.builder()
                                .unitName("Thùng")
                                .conversionFactor(BigDecimal.valueOf(24))
                                .build(),
                        CreateProductUnitConversionRequest.builder()
                                .unitName("Thùng")
                                .conversionFactor(BigDecimal.valueOf(20))
                                .build()
                ))
                .build();

        when(productRepository.existsBySku("SP-COCA-330")).thenReturn(false);

        assertThatThrownBy(() -> productService.createProduct(request, actor))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_UNIT_REQUEST");
    }

    @Test
    @DisplayName("S2-05: Cập nhật thông tin sản phẩm thành công")
    void updateProduct_Success() {
        Product existing = Product.builder()
                .id(1L)
                .sku("SP-COCA-330")
                .name("Coca cũ")
                .baseUnit("Lon")
                .costPrice(BigDecimal.valueOf(200000))
                .build();

        UpdateProductRequest request = UpdateProductRequest.builder()
                .name("Coca mới")
                .baseUnit("Lon")
                .costPrice(BigDecimal.valueOf(210000))
                .packaging("Thùng 24 lon")
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenReturn(existing);
        when(unitConversionService.buildAllUnitsList(any(Product.class))).thenReturn(List.of());

        ProductDetailResponse response = productService.updateProduct(1L, request, actor);

        assertThat(response).isNotNull();
        assertThat(existing.getName()).isEqualTo("Coca mới");
        assertThat(existing.getCostPrice()).isEqualByComparingTo(BigDecimal.valueOf(210000));
        // Đổi giá vốn -> ghi nhật ký nhóm giá (PRICING) kèm giá cũ / mới
        verify(auditLogService).record(
                eq(AuditModule.PRICING),
                eq("UPDATE_COST_PRICE"),
                eq("PRODUCT"),
                eq(1L),
                eq("SP-COCA-330:Coca mới"),
                eq("200000"),
                eq("210000"),
                anyString(),
                eq(actor)
        );
    }

    @Test
    @DisplayName("S2-05: Lấy chi tiết sản phẩm theo SKU -> 200 OK")
    void getProductBySku_Success() {
        Product existing = Product.builder()
                .id(1L)
                .sku("SP-COCA-330")
                .name("Coca-Cola")
                .baseUnit("Lon")
                .build();

        when(productRepository.findBySkuWithConversions("SP-COCA-330")).thenReturn(Optional.of(existing));
        when(unitConversionService.buildAllUnitsList(existing)).thenReturn(List.of());

        ProductDetailResponse response = productService.getProductBySku("SP-COCA-330");

        assertThat(response).isNotNull();
        assertThat(response.getSku()).isEqualTo("SP-COCA-330");
    }

    @Test
    @DisplayName("S2-05: Ngừng kinh doanh sản phẩm (soft-delete sang INACTIVE) để bảo vệ lịch sử giao dịch")
    void deleteProduct_SoftDeletesToInactive() {
        Product existing = Product.builder()
                .id(1L)
                .sku("SP-COCA-330")
                .status("ACTIVE")
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));

        productService.deleteProduct(1L, actor);

        assertThat(existing.getStatus()).isEqualTo("INACTIVE");
        verify(productRepository).save(existing);
        verify(auditLogService).record(
                eq(AuditModule.INVENTORY),
                eq("DEACTIVATE_PRODUCT"),
                eq("PRODUCT"),
                eq(1L),
                eq("SP-COCA-330"),
                eq("ACTIVE"),
                eq("INACTIVE"),
                anyString(),
                eq(actor)
        );
    }

    @Test
    @DisplayName("S2-05: Quản lý kinh doanh xem chi tiết sản phẩm -> Thấy giá vốn (costPrice)")
    void getProductById_AsSalesManager_ShowsCostPrice() {
        Product existing = Product.builder()
                .id(1L)
                .sku("SP-COCA-330")
                .name("Coca-Cola")
                .baseUnit("Lon")
                .costPrice(BigDecimal.valueOf(210000))
                .build();

        UserDetailsImpl salesManager = new UserDetailsImpl(2L, "manager", "Quản lý KD", "mgr@erp.com", "x", true,
                List.of(new SimpleGrantedAuthority("ROLE_SALES_MANAGER")));

        when(productRepository.findByIdWithConversions(1L)).thenReturn(Optional.of(existing));
        when(unitConversionService.buildAllUnitsList(existing)).thenReturn(List.of());

        ProductDetailResponse response = productService.getProductById(1L, salesManager);

        assertThat(response.getCostPrice()).isNotNull();
        assertThat(response.getCostPrice()).isEqualByComparingTo(BigDecimal.valueOf(210000));
    }

    @Test
    @DisplayName("S2-05: Nhân viên kho xem chi tiết sản phẩm -> Giá vốn (costPrice) bị ẩn (null)")
    void getProductById_AsWarehouseStaff_MasksCostPrice() {
        Product existing = Product.builder()
                .id(1L)
                .sku("SP-COCA-330")
                .name("Coca-Cola")
                .baseUnit("Lon")
                .costPrice(BigDecimal.valueOf(210000))
                .build();

        UserDetailsImpl warehouseStaff = new UserDetailsImpl(3L, "warehouse", "Thủ kho", "wh@erp.com", "x", true,
                List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE")));

        when(productRepository.findByIdWithConversions(1L)).thenReturn(Optional.of(existing));
        when(unitConversionService.buildAllUnitsList(existing)).thenReturn(List.of());

        ProductDetailResponse response = productService.getProductById(1L, warehouseStaff);

        // Bảo mật: Nhân viên kho không thấy được giá vốn
        assertThat(response.getCostPrice()).isNull();
    }

    @Test
    @DisplayName("S2-05: Khai báo URL ảnh không hợp lệ -> Ném lỗi INVALID_IMAGE_URL")
    void createProduct_Fails_WhenInvalidImageUrl() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("SP-TEST-IMG")
                .name("Sản phẩm test ảnh")
                .baseUnit("Lon")
                .imageUrl("https://13212312")
                .build();

        when(productRepository.existsBySku("SP-TEST-IMG")).thenReturn(false);

        assertThatThrownBy(() -> productService.createProduct(request, actor))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_IMAGE_URL");
    }

    @Test
    @DisplayName("S2-05: Cập nhật URL ảnh không hợp lệ -> Ném lỗi INVALID_IMAGE_URL")
    void updateProduct_Fails_WhenInvalidImageUrl() {
        Product existing = Product.builder()
                .id(1L)
                .sku("SP-COCA-330")
                .name("Coca cũ")
                .baseUnit("Lon")
                .build();

        UpdateProductRequest request = UpdateProductRequest.builder()
                .name("Coca mới")
                .baseUnit("Lon")
                .imageUrl("https://13212312")
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> productService.updateProduct(1L, request, actor))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_IMAGE_URL");
    }
}
