package com.erp.backend.repository;

import com.erp.backend.dto.order.OrderSearchCriteria;
import com.erp.backend.entity.SalesOrder;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** S4-07: Bộ lọc đơn hàng phải lọc trong câu truy vấn DB và luôn giới hạn NV kinh doanh theo đại lý mình phụ trách. */
@SuppressWarnings({"unchecked", "rawtypes"})
class OrderSpecificationsTest {

    private Root<SalesOrder> root;
    private CriteriaQuery<Object> query;
    private CriteriaBuilder cb;
    private Join customer;
    private Path salesRepPath;
    private Path salesRepIdPath;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        query = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        customer = mock(Join.class);
        salesRepPath = mock(Path.class);
        salesRepIdPath = mock(Path.class);
        when(root.join("customer")).thenReturn(customer);
        lenient().when(customer.get("salesRep")).thenReturn(salesRepPath);
        lenient().when(salesRepPath.get("id")).thenReturn(salesRepIdPath);
    }

    private static OrderSearchCriteria empty() {
        return new OrderSearchCriteria(List.of(), null, null, null, null, null, null);
    }

    @Test
    @DisplayName("S4-07: NV kinh doanh -> luôn thêm điều kiện đại lý do mình phụ trách")
    void salesRep_restrictedToOwnCustomers() {
        OrderSpecifications.search(empty(), 7L).toPredicate(root, query, cb);

        verify(cb).equal(salesRepIdPath, (Object) 7L);
    }

    @Test
    @DisplayName("S4-07: QL kinh doanh không lọc nhân viên -> không thêm điều kiện nhân viên")
    void manager_noSalesRepCondition() {
        OrderSpecifications.search(empty(), null).toPredicate(root, query, cb);

        verify(customer, never()).get("salesRep");
    }

    @Test
    @DisplayName("S4-07: Lọc khoảng ngày tính trọn ngày cuối (createdAt < ngày sau ngày cuối)")
    void dateRange_inclusiveOfLastDay() {
        Path<LocalDateTime> createdAt = mock(Path.class);
        when(root.<LocalDateTime>get("createdAt")).thenReturn(createdAt);
        OrderSearchCriteria c = new OrderSearchCriteria(List.of(), null, null, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);

        OrderSpecifications.search(c, null).toPredicate(root, query, cb);

        verify(cb).greaterThanOrEqualTo(createdAt, LocalDateTime.of(2026, 10, 1, 0, 0));
        verify(cb).lessThan(createdAt, LocalDateTime.of(2026, 11, 1, 0, 0));
    }

    @Test
    @DisplayName("S4-07: Lọc nhiều trạng thái bằng IN")
    void statuses_useIn() {
        Path status = mock(Path.class);
        when(root.get("status")).thenReturn(status);
        when(status.in(any(java.util.Collection.class))).thenReturn(mock(Predicate.class));
        OrderSearchCriteria c = new OrderSearchCriteria(List.of("PENDING_APPROVAL", "APPROVED"),
                null, null, null, null, null, null);

        OrderSpecifications.search(c, null).toPredicate(root, query, cb);

        verify(status).in(List.of("PENDING_APPROVAL", "APPROVED"));
    }

    @Test
    @DisplayName("S4-07: Lọc theo khu vực của đại lý")
    void region_filter() {
        Path region = mock(Path.class);
        Path regionId = mock(Path.class);
        when(customer.get("region")).thenReturn(region);
        when(region.get("id")).thenReturn(regionId);
        OrderSearchCriteria c = new OrderSearchCriteria(List.of(), null, null, 2L, null, null, null);

        OrderSpecifications.search(c, null).toPredicate(root, query, cb);

        verify(cb).equal(regionId, (Object) 2L);
    }
}
