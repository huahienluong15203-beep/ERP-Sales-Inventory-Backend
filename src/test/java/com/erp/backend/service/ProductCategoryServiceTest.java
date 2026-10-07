package com.erp.backend.service;

import com.erp.backend.dto.category.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.ProductCategory;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.ProductCategoryRepository;
import com.erp.backend.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductCategoryServiceTest {

    @Mock private ProductCategoryRepository categoryRepository;
    @Mock private ProductRepository productRepository;

    @InjectMocks private ProductCategoryService service;

    private final List<ProductCategory> all = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(categoryRepository.save(any(ProductCategory.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(categoryRepository.findAll()).thenReturn(all);
        lenient().when(categoryRepository.findById(any())).thenAnswer(inv ->
                all.stream().filter(c -> c.getId().equals(inv.getArgument(0))).findFirst());
    }

    private ProductCategory category(long id, ProductCategory parent) {
        ProductCategory c = ProductCategory.builder()
                .id(id).code("NH-" + id).name("Nhóm " + id)
                .level(parent == null ? 1 : parent.getLevel() + 1)
                .parent(parent)
                .build();
        all.add(c);
        return c;
    }

    private Product product(long id, ProductCategory category) {
        return Product.builder().id(id).sku("SKU-" + id).name("Sản phẩm " + id).baseUnit("lon")
                .productCategory(category).category(category != null ? category.getName() : null).build();
    }

    private CreateCategoryRequest createRequest(Long parentId) {
        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setCode(" do-uong ");
        req.setName(" Đồ uống ");
        req.setParentId(parentId);
        return req;
    }

    @Test
    @DisplayName("S2-06: Tạo nhóm gốc -> cấp 1, mã viết hoa")
    void create_root() {
        CategoryResponse res = service.create(createRequest(null));

        assertThat(res.code()).isEqualTo("DO-UONG");
        assertThat(res.name()).isEqualTo("Đồ uống");
        assertThat(res.level()).isEqualTo(1);
        assertThat(res.parentId()).isNull();
    }

    @Test
    @DisplayName("S2-06: Tạo đủ 3 cấp: nhóm con có cấp = cấp cha + 1")
    void create_threeLevels() {
        ProductCategory level1 = category(1, null);
        ProductCategory level2 = category(2, level1);

        CategoryResponse res = service.create(createRequest(2L));

        assertThat(res.level()).isEqualTo(3);
        assertThat(res.parentId()).isEqualTo(level2.getId());
    }

    @Test
    @DisplayName("S2-06: Vượt quá số cấp tối đa -> 400 MAX_LEVEL_EXCEEDED")
    void create_tooDeep() {
        ProductCategory parent = null;
        for (long i = 1; i <= ProductCategoryService.MAX_LEVEL; i++) {
            parent = category(i, parent);
        }

        assertThatThrownBy(() -> service.create(createRequest((long) ProductCategoryService.MAX_LEVEL)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("MAX_LEVEL_EXCEEDED");
    }

    @Test
    @DisplayName("S2-06: Nhóm cha không tồn tại -> 400 PARENT_NOT_FOUND")
    void create_parentNotFound() {
        assertThatThrownBy(() -> service.create(createRequest(99L)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("PARENT_NOT_FOUND");
    }

    @Test
    @DisplayName("S2-06: Trùng mã nhóm -> 409 CATEGORY_CODE_EXISTS")
    void create_duplicateCode() {
        when(categoryRepository.existsByCodeIgnoreCase("DO-UONG")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("S2-06: Danh sách cây kèm số sản phẩm trực tiếp của từng nhóm")
    void getAll_withCounts() {
        ProductCategory root = category(1, null);
        ProductCategory child = category(2, root);
        when(categoryRepository.findAllByOrderByLevelAscNameAsc()).thenReturn(List.of(root, child));
        when(productRepository.countByCategory()).thenReturn(List.<Object[]>of(new Object[]{2L, 7L}));

        List<CategoryResponse> res = service.getAll();

        assertThat(res).hasSize(2);
        assertThat(res.get(0).productCount()).isZero();
        assertThat(res.get(1).productCount()).isEqualTo(7L);
        assertThat(res.get(1).parentId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("S2-06: Chuyển sản phẩm sang nhóm khác -> đổi cả nhóm và tên nhóm của sản phẩm")
    void moveProducts_success() {
        ProductCategory oldGroup = category(1, null);
        ProductCategory newGroup = category(2, null);
        Product p1 = product(10, oldGroup);
        Product p2 = product(11, null);
        when(productRepository.findAllById(anyCollection())).thenReturn(List.of(p1, p2));
        MoveProductsRequest req = new MoveProductsRequest();
        req.setProductIds(List.of(10L, 11L, 10L));

        MoveProductsResponse res = service.moveProducts(2L, req);

        assertThat(res.movedCount()).isEqualTo(2);
        assertThat(p1.getProductCategory()).isEqualTo(newGroup);
        assertThat(p1.getCategory()).isEqualTo("Nhóm 2");
        assertThat(p2.getProductCategory()).isEqualTo(newGroup);
    }

    @Test
    @DisplayName("S2-06: Chuyển sản phẩm không tồn tại -> 404 PRODUCT_NOT_FOUND, không đổi gì")
    void moveProducts_missingProduct() {
        category(2, null);
        when(productRepository.findAllById(anyCollection())).thenReturn(List.of(product(10, null)));
        MoveProductsRequest req = new MoveProductsRequest();
        req.setProductIds(List.of(10L, 99L));

        assertThatThrownBy(() -> service.moveProducts(2L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("PRODUCT_NOT_FOUND");
        verify(productRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("S2-06: Xoá nhóm còn sản phẩm -> 409 CATEGORY_HAS_PRODUCTS")
    void delete_hasProducts() {
        category(1, null);
        when(productRepository.existsByProductCategory_Id(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CATEGORY_HAS_PRODUCTS");
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("S2-06: Xoá nhóm còn nhóm con -> 409 CATEGORY_HAS_CHILDREN")
    void delete_hasChildren() {
        category(1, null);
        when(categoryRepository.existsByParent_Id(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CATEGORY_HAS_CHILDREN");
    }

    @Test
    @DisplayName("S2-06: Xoá nhóm trống -> xoá được")
    void delete_empty() {
        ProductCategory c = category(1, null);

        service.delete(1L);

        verify(categoryRepository).delete(c);
    }

    @Test
    @DisplayName("S2-06: Xem sản phẩm cả nhóm con -> lấy id của nhóm và toàn bộ nhóm con, cháu")
    @SuppressWarnings("unchecked")
    void getProducts_includeSubgroups() {
        ProductCategory root = category(1, null);
        ProductCategory child = category(2, root);
        category(3, child);
        category(4, null);
        when(productRepository.findByProductCategory_IdIn(anyCollection(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product(10, child))));

        PageResponse<CategoryProductItem> res = service.getProducts(1L, true, 0, 20);

        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(productRepository).findByProductCategory_IdIn(ids.capture(), any(Pageable.class));
        assertThat(ids.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(res.content()).hasSize(1);
        assertThat(res.content().get(0).categoryName()).isEqualTo("Nhóm 2");
    }

    @Test
    @DisplayName("S2-06: Đổi tên nhóm -> tên nhóm của các sản phẩm trong nhóm đổi theo")
    void update_renamesProducts() {
        ProductCategory c = category(1, null);
        Product p = product(10, c);
        when(productRepository.findByProductCategory_Id(1L)).thenReturn(List.of(p));
        CategoryRequest req = new CategoryRequest();
        req.setName("Nước giải khát");

        CategoryResponse res = service.update(1L, req);

        assertThat(res.name()).isEqualTo("Nước giải khát");
        assertThat(res.code()).isEqualTo("NH-1");
        assertThat(p.getCategory()).isEqualTo("Nước giải khát");
    }

    @Test
    @DisplayName("S2-06: Nhóm không tồn tại -> 404")
    void delete_notFound() {
        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
