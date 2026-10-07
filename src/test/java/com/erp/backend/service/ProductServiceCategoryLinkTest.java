package com.erp.backend.service;

import com.erp.backend.dto.product.CreateProductRequest;
import com.erp.backend.dto.product.ProductDetailResponse;
import com.erp.backend.dto.product.UpdateProductRequest;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.ProductCategory;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.ProductCategoryRepository;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** S2-06: Tạo / sửa sản phẩm gắn thẳng vào cây nhóm hàng bằng categoryId. */
@ExtendWith(MockitoExtension.class)
class ProductServiceCategoryLinkTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductUnitConversionRepository unitConversionRepository;
    @Mock private ProductUnitConversionService unitConversionService;
    @Mock private AuditLogService auditLogService;
    @Mock private ProductCategoryRepository productCategoryRepository;

    @InjectMocks private ProductService productService;

    private final UserDetailsImpl manager = new UserDetailsImpl(2L, "sales_manager", "QL kinh doanh",
            "manager@erp.com", "x", true, List.of(new SimpleGrantedAuthority("ROLE_SALES_MANAGER")));

    private final ProductCategory coGa = ProductCategory.builder().id(3L).code("CO-GA").name("Có ga").level(3).build();
    private final ProductCategory nuocSuoi = ProductCategory.builder().id(4L).code("NUOC-SUOI").name("Nước suối").level(2).build();

    @BeforeEach
    void setUp() {
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productCategoryRepository.findById(3L)).thenReturn(Optional.of(coGa));
        lenient().when(productCategoryRepository.findById(4L)).thenReturn(Optional.of(nuocSuoi));
    }

    private CreateProductRequest createRequest(String categoryText, Long categoryId) {
        return CreateProductRequest.builder()
                .sku("SP-QA-01").name("Coca QA").baseUnit("Lon")
                .category(categoryText).categoryId(categoryId)
                .build();
    }

    private UpdateProductRequest updateRequest(String categoryText, Long categoryId) {
        return UpdateProductRequest.builder()
                .name("Coca QA").baseUnit("Lon")
                .category(categoryText).categoryId(categoryId)
                .build();
    }

    private Product existing(ProductCategory category) {
        Product p = Product.builder().id(10L).sku("SP-QA-01").name("Coca QA").baseUnit("Lon")
                .productCategory(category).category(category != null ? category.getName() : "Nhóm cũ").build();
        when(productRepository.findById(10L)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    @DisplayName("S2-06: Tạo sản phẩm có categoryId -> gắn vào cây, tên nhóm lấy theo nhóm thật")
    void create_withCategoryId_linksToTree() {
        ProductDetailResponse res = productService.createProduct(createRequest("gõ sai tên", 3L), manager);

        assertThat(res.getCategoryId()).isEqualTo(3L);
        assertThat(res.getCategory()).isEqualTo("Có ga");
    }

    @Test
    @DisplayName("S2-06: Tạo sản phẩm với categoryId không tồn tại -> 400 CATEGORY_NOT_FOUND, không lưu")
    void create_unknownCategory_rejected() {
        when(productCategoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.createProduct(createRequest(null, 99L), manager))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CATEGORY_NOT_FOUND");
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("S2-06: Tạo sản phẩm không có categoryId -> giữ cách cũ, lưu tên nhóm dạng chữ")
    void create_withoutCategoryId_keepsText() {
        ProductDetailResponse res = productService.createProduct(createRequest(" Bánh kẹo ", null), manager);

        assertThat(res.getCategoryId()).isNull();
        assertThat(res.getCategory()).isEqualTo("Bánh kẹo");
    }

    @Test
    @DisplayName("S2-06: Sửa sản phẩm sang nhóm khác bằng categoryId")
    void update_changeCategory() {
        Product p = existing(coGa);

        ProductDetailResponse res = productService.updateProduct(10L, updateRequest(null, 4L), manager);

        assertThat(p.getProductCategory()).isEqualTo(nuocSuoi);
        assertThat(res.getCategoryId()).isEqualTo(4L);
        assertThat(res.getCategory()).isEqualTo("Nước suối");
    }

    @Test
    @DisplayName("S2-06: Sửa sản phẩm đang trong cây, không gửi categoryId -> giữ nhóm, tên nhóm không bị lệch")
    void update_withoutCategoryId_keepsTreeGroup() {
        Product p = existing(coGa);

        ProductDetailResponse res = productService.updateProduct(10L, updateRequest("Tên gõ tay khác", null), manager);

        assertThat(p.getProductCategory()).isEqualTo(coGa);
        assertThat(res.getCategory()).isEqualTo("Có ga");
        assertThat(res.getCategoryId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("S2-06: Sửa sản phẩm chưa trong cây, không gửi categoryId -> sửa tên nhóm dạng chữ như cũ")
    void update_notInTree_keepsTextBehaviour() {
        existing(null);

        ProductDetailResponse res = productService.updateProduct(10L, updateRequest("Nhóm mới", null), manager);

        assertThat(res.getCategoryId()).isNull();
        assertThat(res.getCategory()).isEqualTo("Nhóm mới");
    }
}
