package com.erp.backend.service;

import com.erp.backend.dto.order.OrderSearchCriteria;
import com.erp.backend.dto.order.OrderSummaryResponse;
import com.erp.backend.dto.order.OrderTotalsResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.SalesOrderRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.erp.backend.service.CustomerTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class OrderQueryServiceTest {

    @Mock private SalesOrderRepository orderRepository;
    @Mock private com.erp.backend.repository.CustomerRepository customerRepository;

    @InjectMocks private OrderQueryService service;

    private static OrderSearchCriteria criteria(List<String> statuses, LocalDate from, LocalDate to) {
        return new OrderSearchCriteria(statuses, null, null, null, from, to, "  DH2610 ");
    }

    @Test
    @DisplayName("S4-07: Lọc nhiều trạng thái (vd ?status=pending_approval,approved), chuẩn hoá chữ hoa và bỏ trùng")
    void normalize_multipleStatuses() {
        OrderSearchCriteria c = service.normalize(criteria(List.of("pending_approval, approved", "APPROVED"), null, null));

        assertThat(c.statuses()).containsExactly("PENDING_APPROVAL", "APPROVED");
        assertThat(c.keyword()).isEqualTo("DH2610");
    }

    @Test
    @DisplayName("S4-07: Trạng thái sai định dạng -> 400")
    void normalize_invalidStatus_badRequest() {
        assertThatThrownBy(() -> service.normalize(criteria(List.of("DRAFT'; drop"), null, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ORDER_STATUS");
    }

    @Test
    @DisplayName("S4-07: Từ ngày sau đến ngày -> 400")
    void normalize_fromAfterTo_badRequest() {
        assertThatThrownBy(() -> service.normalize(criteria(null, LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 1))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_DATE_RANGE");
    }

    @Test
    @DisplayName("S4-07: Khoảng ngày quá 1 năm -> 400")
    void normalize_rangeTooLong_badRequest() {
        assertThatThrownBy(() -> service.normalize(criteria(null, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 1))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "DATE_RANGE_TOO_LONG");
    }

    @Test
    @DisplayName("S4-07: Không truyền bộ lọc -> lấy tất cả, không lỗi")
    void normalize_null_ok() {
        OrderSearchCriteria c = service.normalize(null);

        assertThat(c.statuses()).isEmpty();
        assertThat(c.keyword()).isNull();
    }

    @Test
    @DisplayName("S4-07: Danh sách trả kèm nhân viên phụ trách, khu vực; cỡ trang tối đa 100")
    void search_mapsSalesRepAndRegion() {
        Customer customer = customer(6, salesRep(7));
        SalesOrder order = SalesOrder.builder().id(100L).code("DH261008-AAAA").customer(customer)
                .status(SalesOrder.STATUS_APPROVED).totalAmount(new BigDecimal("500000"))
                .createdAt(LocalDateTime.of(2026, 10, 8, 9, 0)).build();
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(order)));

        PageResponse<OrderSummaryResponse> res = service.search(null, -1, 5000, actor(3, "ROLE_SALES_MANAGER"));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        OrderSummaryResponse row = res.content().get(0);
        assertThat(row.salesRepId()).isEqualTo(7L);
        assertThat(row.salesRepName()).isEqualTo(customer.getSalesRep().getFullName());
        assertThat(row.regionName()).isEqualTo("Khu vực 1");
        assertThat(row.createdAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 9, 0));
    }

    @Test
    @DisplayName("S4-07: Tổng tiền của toàn bộ kết quả đang lọc lấy từ DB")
    void totals_fromRepository() {
        when(orderRepository.sumTotals(any(Specification.class)))
                .thenReturn(new OrderTotalsResponse(42, new BigDecimal("123456789.00")));

        OrderTotalsResponse res = service.totals(criteria(List.of("APPROVED"), null, null), actor(7, "ROLE_SALES_REP"));

        assertThat(res.orderCount()).isEqualTo(42);
        assertThat(res.totalAmount()).isEqualByComparingTo("123456789");
    }

    @Test
    @DisplayName("S4-07: Tổng tiền cũng kiểm tra bộ lọc (khoảng ngày sai -> 400, không truy vấn DB)")
    void totals_invalidRange_badRequest() {
        assertThatThrownBy(() -> service.totals(criteria(null, LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 1)),
                actor(3, "ROLE_SALES_MANAGER")))
                .isInstanceOf(BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(orderRepository);
    }
}
