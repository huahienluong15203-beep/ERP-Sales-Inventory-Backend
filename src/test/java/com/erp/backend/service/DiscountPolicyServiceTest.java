package com.erp.backend.service;

import com.erp.backend.dto.discount.*;
import com.erp.backend.entity.*;
import com.erp.backend.repository.DiscountPolicyRepository;
import com.erp.backend.repository.ProductCategoryRepository;
import com.erp.backend.repository.ProductRepository;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiscountPolicyServiceTest {

    @Mock private DiscountPolicyRepository policyRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductCategoryRepository categoryRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private DiscountPolicyService service;

    private final UserDetailsImpl manager = new UserDetailsImpl(2L, "sales_manager", "QL kinh doanh",
            "m@erp.com", "x", true, List.of(new SimpleGrantedAuthority("ROLE_SALES_MANAGER")));
    private final ProductCategory doUong = ProductCategory.builder().id(1L).code("DU").name("Đồ uống").level(1).build();
    private final ProductCategory nuocNgot = ProductCategory.builder().id(2L).code("NN").name("Nước ngọt").level(2).parent(doUong).build();
    private final Product coca = Product.builder().id(10L).sku("SP-COCA").name("Coca lon").baseUnit("Lon")
            .productCategory(nuocNgot).build();
    private final LocalDate day = LocalDate.of(2026, 10, 10);

    @BeforeEach
    void setUp() {
        lenient().when(productRepository.findBySku("SP-COCA")).thenReturn(Optional.of(coca));
        lenient().when(categoryRepository.findById(2L)).thenReturn(Optional.of(nuocNgot));
        lenient().when(policyRepository.save(any(DiscountPolicy.class))).thenAnswer(inv -> {
            DiscountPolicy p = inv.getArgument(0);
            if (p.getId() == null) p.setId(1L);
            return p;
        });
    }

    private DiscountTierRequest tier(String qty, String value) {
        DiscountTierRequest t = new DiscountTierRequest();
        t.setMinQuantity(new BigDecimal(qty));
        t.setDiscountValue(new BigDecimal(value));
        return t;
    }

    private DiscountPolicyRequest request(String scope, String type, DiscountTierRequest... tiers) {
        DiscountPolicyRequest r = new DiscountPolicyRequest();
        r.setCode(" ck-coca ");
        r.setName("Chiết khấu Coca");
        r.setScope(scope);
        r.setProductSku("sp-coca");
        r.setCategoryId(2L);
        r.setDiscountType(type);
        r.setStartDate(LocalDate.of(2026, 10, 1));
        r.setTiers(new ArrayList<>(List.of(tiers)));
        return r;
    }

    private DiscountPolicy policy(long id, String scope, String type, String... qtyValue) {
        DiscountPolicy p = DiscountPolicy.builder().id(id).code("CK-" + id).name("CS " + id).scope(scope)
                .discountType(type).startDate(LocalDate.of(2026, 10, 1)).build();
        for (int i = 0; i < qtyValue.length; i += 2) {
            p.addTier(DiscountTier.builder().minQuantity(new BigDecimal(qtyValue[i]))
                    .discountValue(new BigDecimal(qtyValue[i + 1])).build());
        }
        return p;
    }

    @Test
    @DisplayName("S3-01: Tạo chính sách theo SKU, các bậc được sắp theo số lượng, ghi nhật ký")
    void create_productPolicy() {
        DiscountPolicyResponse res = service.create(request("product", "percent", tier("96", "5"), tier("48", "3")), manager);

        assertThat(res.code()).isEqualTo("CK-COCA");
        assertThat(res.scope()).isEqualTo("PRODUCT");
        assertThat(res.productSku()).isEqualTo("SP-COCA");
        assertThat(res.categoryId()).isNull();
        assertThat(res.tiers()).extracting(DiscountTierResponse::minQuantity)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("48"), new BigDecimal("96"));
        verify(auditLogService).record(eq(AuditModule.PRICING), eq("CREATE_DISCOUNT_POLICY"), any(), any(), any(), any(), any(), any(), eq(manager));
    }

    @Test
    @DisplayName("S3-01: Tạo chính sách theo nhóm hàng")
    void create_categoryPolicy() {
        DiscountPolicyResponse res = service.create(request("CATEGORY", "AMOUNT_PER_UNIT", tier("100", "400")), manager);

        assertThat(res.categoryName()).isEqualTo("Nước ngọt");
        assertThat(res.productId()).isNull();
    }

    @Test
    @DisplayName("S3-01: Bậc sai -> bị chặn (không có bậc, % > 100, trùng số lượng, mua nhiều mà chiết khấu thấp hơn)")
    void create_invalidTiers() {
        assertThatThrownBy(() -> service.create(request("PRODUCT", "PERCENT"), manager))
                .extracting("code").isEqualTo("TIERS_REQUIRED");
        assertThatThrownBy(() -> service.create(request("PRODUCT", "PERCENT", tier("10", "101")), manager))
                .extracting("code").isEqualTo("INVALID_TIER_VALUE");
        assertThatThrownBy(() -> service.create(request("PRODUCT", "PERCENT", tier("10", "2"), tier("10.0", "3")), manager))
                .extracting("code").isEqualTo("DUPLICATE_TIER");
        assertThatThrownBy(() -> service.create(request("PRODUCT", "PERCENT", tier("10", "5"), tier("50", "3")), manager))
                .extracting("code").isEqualTo("TIER_VALUE_DECREASING");
        verify(policyRepository, never()).save(any());
    }

    @Test
    @DisplayName("S3-01: Phạm vi / kiểu chiết khấu sai, trùng mã -> bị chặn")
    void create_invalidHeader() {
        assertThatThrownBy(() -> service.create(request("STORE", "PERCENT", tier("1", "1")), manager))
                .extracting("code").isEqualTo("INVALID_SCOPE");
        assertThatThrownBy(() -> service.create(request("PRODUCT", "FREE", tier("1", "1")), manager))
                .extracting("code").isEqualTo("INVALID_DISCOUNT_TYPE");
        when(policyRepository.existsByCodeIgnoreCase("CK-COCA")).thenReturn(true);
        assertThatThrownBy(() -> service.create(request("PRODUCT", "PERCENT", tier("1", "1")), manager))
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("S3-01: Lấy bậc cao nhất đạt được: mua 120 -> bậc 96 (5%) -> 500đ/lon, 60.000đ")
    void calculate_picksHighestReachedTier() {
        when(policyRepository.findEffective(eq(10L), anyCollection(), eq(day)))
                .thenReturn(List.of(policy(1, "PRODUCT", "PERCENT", "48", "3", "96", "5", "200", "7")));

        DiscountCalculationResponse res = service.calculate(coca, new BigDecimal("120"), new BigDecimal("10000"), day);

        assertThat(res.applied().tierMinQuantity()).isEqualByComparingTo("96");
        assertThat(res.applied().discountPerUnit()).isEqualByComparingTo("500");
        assertThat(res.discountAmount()).isEqualByComparingTo("60000");
        assertThat(res.grossAmount()).isEqualByComparingTo("1200000");
        assertThat(res.netAmount()).isEqualByComparingTo("1140000");
    }

    @Test
    @DisplayName("S3-01: Nhiều chính sách -> ưu tiên chính sách riêng theo SKU trước chính sách theo nhóm hàng")
    void calculate_prefersProductScopeOverCategory() {
        when(policyRepository.findEffective(eq(10L), anyCollection(), eq(day))).thenReturn(List.of(
                policy(1, "PRODUCT", "PERCENT", "96", "5"),            // 500đ/lon -> 60.000
                policy(2, "CATEGORY", "AMOUNT_PER_UNIT", "100", "600") // 600đ/lon -> 72.000
        ));

        DiscountCalculationResponse res = service.calculate(coca, new BigDecimal("120"), new BigDecimal("10000"), day);

        assertThat(res.applied().policyId()).isEqualTo(1L);
        assertThat(res.discountAmount()).isEqualByComparingTo("60000");
        assertThat(res.candidates()).hasSize(2);
    }

    @Test
    @DisplayName("S3-01: Bằng tiền nhau -> ưu tiên chính sách theo SKU")
    void calculate_tiePrefersProductScope() {
        when(policyRepository.findEffective(eq(10L), anyCollection(), eq(day))).thenReturn(List.of(
                policy(1, "CATEGORY", "AMOUNT_PER_UNIT", "10", "500"),
                policy(2, "PRODUCT", "PERCENT", "10", "5")));

        DiscountCalculationResponse res = service.calculate(coca, new BigDecimal("20"), new BigDecimal("10000"), day);

        assertThat(res.applied().policyId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("S3-01: Chưa đạt bậc nào -> chiết khấu 0; giảm theo tiền không vượt đơn giá")
    void calculate_noTierAndCap() {
        when(policyRepository.findEffective(eq(10L), anyCollection(), eq(day)))
                .thenReturn(List.of(policy(1, "PRODUCT", "PERCENT", "48", "3")));
        DiscountCalculationResponse none = service.calculate(coca, new BigDecimal("10"), new BigDecimal("10000"), day);
        assertThat(none.applied()).isNull();
        assertThat(none.discountAmount()).isEqualByComparingTo("0");
        assertThat(none.netAmount()).isEqualByComparingTo("100000");

        when(policyRepository.findEffective(eq(10L), anyCollection(), eq(day)))
                .thenReturn(List.of(policy(2, "PRODUCT", "AMOUNT_PER_UNIT", "1", "15000")));
        DiscountCalculationResponse capped = service.calculate(coca, new BigDecimal("2"), new BigDecimal("10000"), day);
        assertThat(capped.applied().discountPerUnit()).isEqualByComparingTo("10000");
        assertThat(capped.netAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("S3-01: Chính sách cho nhóm cha cũng áp cho sản phẩm ở nhóm con")
    @SuppressWarnings("unchecked")
    void calculate_includesParentCategories() {
        when(policyRepository.findEffective(eq(10L), anyCollection(), eq(day))).thenReturn(List.of());

        service.calculate(coca, BigDecimal.ONE, BigDecimal.TEN, day);

        verify(policyRepository).findEffective(eq(10L), argThat((Collection<Long> ids) ->
                ids.containsAll(List.of(1L, 2L)) && ids.size() == 2), eq(day));
    }

    @Test
    @DisplayName("S3-01: Tính qua API: số lượng <= 0 hoặc SKU không tồn tại -> 400")
    void calculate_invalidRequest() {
        DiscountCalculationRequest r = new DiscountCalculationRequest();
        r.setProductSku("SP-COCA");
        r.setQuantity(BigDecimal.ZERO);
        r.setUnitPrice(BigDecimal.TEN);
        assertThatThrownBy(() -> service.calculate(r)).extracting("code").isEqualTo("INVALID_QUANTITY");

        r.setProductSku("SP-X");
        assertThatThrownBy(() -> service.calculate(r)).extracting("code").isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("S3-01: Ngừng áp dụng chính sách (không xoá)")
    void changeStatus_inactive() {
        DiscountPolicy p = policy(1, "PRODUCT", "PERCENT", "1", "1");
        when(policyRepository.findById(1L)).thenReturn(Optional.of(p));

        DiscountPolicyResponse res = service.changeStatus(1L, "inactive", manager);

        assertThat(res.status()).isEqualTo("INACTIVE");
        assertThatThrownBy(() -> service.changeStatus(1L, "DELETED", manager)).extracting("code").isEqualTo("INVALID_STATUS");
    }

    @Test
    @DisplayName("S3-01: Danh sách lọc + phân trang phía server")
    @SuppressWarnings("unchecked")
    void search_pagedOnServer() {
        when(policyRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenAnswer(inv -> new org.springframework.data.domain.PageImpl<DiscountPolicy>(List.of(), inv.getArgument(1), 25));

        com.erp.backend.dto.user.PageResponse<DiscountPolicyResponse> res = service.search("expired", "category", "bia", 1, 10);

        assertThat(res.page()).isEqualTo(1);
        assertThat(res.size()).isEqualTo(10);
        assertThat(res.totalElements()).isEqualTo(25);
        assertThat(res.totalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("S3-01: Lọc trạng thái / phạm vi không hợp lệ -> 400")
    void search_invalidFilters() {
        assertThatThrownBy(() -> service.search("DELETED", null, null, 0, 20))
                .hasFieldOrPropertyWithValue("code", "INVALID_STATUS");
        assertThatThrownBy(() -> service.search(null, "SKU", null, 0, 20))
                .hasFieldOrPropertyWithValue("code", "INVALID_SCOPE");
    }

    @Test
    @DisplayName("S3-01: Thống kê tính trên toàn bộ dữ liệu")
    void stats_countsAll() {
        when(policyRepository.count()).thenReturn(6L);
        when(policyRepository.countActive(any(LocalDate.class))).thenReturn(4L);
        when(policyRepository.countByScope("PRODUCT")).thenReturn(4L);
        when(policyRepository.countByScope("CATEGORY")).thenReturn(2L);

        assertThat(service.stats()).isEqualTo(new DiscountPolicyStatsResponse(6, 4, 4, 2));
    }
}
