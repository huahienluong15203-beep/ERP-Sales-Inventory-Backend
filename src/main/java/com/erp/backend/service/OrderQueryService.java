package com.erp.backend.service;

import com.erp.backend.dto.order.OrderSearchCriteria;
import com.erp.backend.dto.order.OrderSummaryResponse;
import com.erp.backend.dto.order.OrderTotalsResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.OrderSpecifications;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

/**
 * S4-07 & S5-01: Danh sách đơn hàng và bộ lọc.
 * - Lọc theo trạng thái (một hoặc nhiều), đại lý, nhân viên, khu vực, khoảng ngày tạo đơn, từ khoá.
 * - Tổng số đơn và tổng tiền của toàn bộ kết quả đang lọc (tính trong DB, không cộng trên trang).
 * - NV kinh doanh chỉ thấy đơn của đại lý mình phụ trách.
 * - S5-01: Đại lý (ROLE_CUSTOMER) chỉ thấy đơn hàng của chính mình.
 */
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final long MAX_RANGE_DAYS = 366;
    // Mã trạng thái: chữ hoa và gạch dưới (S4-06 sẽ thêm các trạng thái soạn hàng / xuất / giao)
    private static final java.util.regex.Pattern STATUS_FORMAT = java.util.regex.Pattern.compile("[A-Z_]{1,30}");

    private final SalesOrderRepository orderRepository;
    private final CustomerRepository customerRepository;

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> search(OrderSearchCriteria criteria, int page, int size, UserDetailsImpl actor) {
        OrderSearchCriteria c = normalize(criteria);
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        Long restrictedRepId = null;
        Long restrictedCustId = null;

        if (CustomerAccess.isCustomer(actor)) {
            Customer cust = CustomerAccess.resolveCustomerForActor(actor, customerRepository);
            if (cust == null) {
                return PageResponse.of(Page.empty());
            }
            restrictedCustId = cust.getId();
        } else {
            restrictedRepId = CustomerAccess.restrictedSalesRepId(actor);
        }

        return PageResponse.of(orderRepository.findAll(
                        OrderSpecifications.search(c, restrictedRepId, restrictedCustId),
                        PageRequest.of(safePage, safeSize,
                                Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by("id").descending())))
                .map(OrderQueryService::toSummary));
    }

    @Transactional(readOnly = true)
    public OrderTotalsResponse totals(OrderSearchCriteria criteria, UserDetailsImpl actor) {
        OrderSearchCriteria c = normalize(criteria);

        Long restrictedRepId = null;
        Long restrictedCustId = null;

        if (CustomerAccess.isCustomer(actor)) {
            Customer cust = CustomerAccess.resolveCustomerForActor(actor, customerRepository);
            if (cust == null) {
                return new OrderTotalsResponse(0, BigDecimal.ZERO);
            }
            restrictedCustId = cust.getId();
        } else {
            restrictedRepId = CustomerAccess.restrictedSalesRepId(actor);
        }

        return orderRepository.sumTotals(OrderSpecifications.search(c, restrictedRepId, restrictedCustId));
    }

    /** Chuẩn hoá trạng thái (chữ hoa, bỏ trùng), kiểm tra khoảng ngày. */
    OrderSearchCriteria normalize(OrderSearchCriteria c) {
        if (c == null) {
            c = new OrderSearchCriteria(null, null, null, null, null, null, null);
        }
        List<String> statuses = c.statuses() == null ? List.of() : c.statuses().stream()
                .filter(StringUtils::hasText)
                .flatMap(s -> Arrays.stream(s.split(",")))
                .map(s -> s.trim().toUpperCase())
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        for (String s : statuses) {
            if (!STATUS_FORMAT.matcher(s).matches()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_STATUS",
                        "Trạng thái đơn không hợp lệ: " + s, "status");
            }
        }
        if (c.fromDate() != null && c.toDate() != null) {
            if (c.fromDate().isAfter(c.toDate())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                        "Ngày bắt đầu không được sau ngày kết thúc", "fromDate");
            }
            if (ChronoUnit.DAYS.between(c.fromDate(), c.toDate()) > MAX_RANGE_DAYS) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "DATE_RANGE_TOO_LONG",
                        "Khoảng thời gian lọc tối đa 1 năm", "toDate");
            }
        }
        String keyword = StringUtils.hasText(c.keyword()) ? c.keyword().trim() : null;
        return new OrderSearchCriteria(statuses, c.customerId(), c.salesRepId(), c.regionId(),
                c.fromDate(), c.toDate(), keyword);
    }

    static OrderSummaryResponse toSummary(SalesOrder o) {
        Customer c = o.getCustomer();
        boolean hasShortage = o.getLines() != null && o.getLines().stream().anyMatch(l ->
                l.getDeliveredQuantity() != null && l.getQuantity() != null
                        && l.getDeliveredQuantity().compareTo(l.getQuantity()) < 0
        );

        return new OrderSummaryResponse(o.getId(), o.getCode(), o.getStatus(), c.getId(), c.getCode(), c.getName(),
                o.getDesiredDeliveryDate(), o.getLines().size(), o.getTotalAmount(), o.getCreatedByUsername(),
                o.getUpdatedAt(),
                c.getSalesRep() != null ? c.getSalesRep().getId() : null,
                c.getSalesRep() != null ? c.getSalesRep().getFullName() : null,
                c.getRegion() != null ? c.getRegion().getId() : null,
                c.getRegion() != null ? c.getRegion().getName() : null,
                o.getCreatedAt(),
                hasShortage);
    }
}
