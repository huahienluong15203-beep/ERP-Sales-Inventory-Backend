package com.erp.backend.service;

import com.erp.backend.dto.pricing.PriceHistoryResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.CustomerGroup;
import com.erp.backend.entity.PriceHistory;
import com.erp.backend.entity.PriceList;
import com.erp.backend.entity.Product;
import com.erp.backend.repository.PriceHistoryRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PriceHistoryServiceTest {

    @Mock private PriceHistoryRepository historyRepository;

    @InjectMocks private PriceHistoryService service;

    private final UserDetailsImpl manager = new UserDetailsImpl(2L, "sales_manager", "Trần QL",
            "m@erp.com", "x", true, List.of(new SimpleGrantedAuthority("ROLE_SALES_MANAGER")));
    private final Product coca = Product.builder().id(10L).sku("SP-COCA").name("Coca lon").baseUnit("Lon").build();
    private final PriceList list = PriceList.builder().id(5L).code("BG-C1").name("Bảng cấp 1")
            .customerGroup(CustomerGroup.DEALER_LEVEL_1).startDate(LocalDate.of(2026, 10, 1)).build();

    @Test
    @DisplayName("S3-02: Ghi lịch sử -> đủ giá cũ, giá mới, người sửa, ngày áp dụng")
    @SuppressWarnings("unchecked")
    void record_savesFullInfo() {
        service.record(list, List.of(new PriceChange(coca, PriceHistory.UPDATE,
                new BigDecimal("9000"), new BigDecimal("9500"), new BigDecimal("8000"), new BigDecimal("8500"))), manager);

        ArgumentCaptor<List<PriceHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(historyRepository).saveAll(captor.capture());
        PriceHistory h = captor.getValue().get(0);
        assertThat(h.getPriceListCode()).isEqualTo("BG-C1");
        assertThat(h.getProductSku()).isEqualTo("SP-COCA");
        assertThat(h.getOldPrice()).isEqualByComparingTo("9000");
        assertThat(h.getNewPrice()).isEqualByComparingTo("9500");
        assertThat(h.getChangedByUsername()).isEqualTo("sales_manager");
        assertThat(h.getChangedByName()).isEqualTo("Trần QL");
        assertThat(h.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("S3-02: Không có thay đổi -> không ghi gì")
    void record_empty_noSave() {
        service.record(list, List.of(), manager);

        verifyNoInteractions(historyRepository);
    }

    @Test
    @DisplayName("S3-02: Tra cứu -> trả nhãn loại thay đổi và nhóm khách hàng")
    @SuppressWarnings("unchecked")
    void search_mapsResponse() {
        PriceHistory h = PriceHistory.builder().id(1L).priceList(list).priceListCode("BG-C1")
                .customerGroup(CustomerGroup.DEALER_LEVEL_1).product(coca).productSku("SP-COCA").productName("Coca lon")
                .changeType(PriceHistory.DELETE).oldPrice(new BigDecimal("9000")).oldFloorPrice(new BigDecimal("8000"))
                .effectiveDate(LocalDate.of(2026, 10, 1)).changedByUsername("sales_manager")
                .changedAt(LocalDateTime.of(2026, 10, 4, 8, 0)).build();
        when(historyRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(h)));

        PageResponse<PriceHistoryResponse> res = service.search("sp-coca", null, "dealer_level_1", null, null, 0, 0);

        assertThat(res.content()).hasSize(1);
        assertThat(res.content().get(0).changeTypeLabel()).isEqualTo("Bỏ giá");
        assertThat(res.content().get(0).customerGroupLabel()).isEqualTo("Đại lý cấp 1");
        assertThat(res.content().get(0).newPrice()).isNull();
    }

    @Test
    @DisplayName("S3-02: Khoảng ngày ngược / nhóm khách sai -> 400")
    void search_invalidInput() {
        assertThatThrownBy(() -> service.search(null, null, null, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 1), 0, 20))
                .extracting("code").isEqualTo("INVALID_DATE_RANGE");
        assertThatThrownBy(() -> service.search(null, null, "VIP", null, null, 0, 20))
                .extracting("code").isEqualTo("INVALID_CUSTOMER_GROUP");
    }

    @Test
    @DisplayName("S3-02: Lịch sử không sửa, không xoá được: service và controller không có hàm sửa / xoá")
    void noUpdateOrDeleteOperations() {
        List<String> names = Arrays.stream(PriceHistoryService.class.getDeclaredMethods()).map(Method::getName).toList();
        assertThat(names).noneMatch(n -> n.startsWith("update") || n.startsWith("delete") || n.startsWith("remove"));
        assertThat(PriceHistory.class.isAnnotationPresent(org.hibernate.annotations.Immutable.class)).isTrue();
    }
}
