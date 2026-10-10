package com.erp.backend.service;

import com.erp.backend.dto.order.OrderLineDeliveryRequest;
import com.erp.backend.dto.order.OrderResponse;
import com.erp.backend.dto.order.OrderSearchCriteria;
import com.erp.backend.dto.order.OrderSummaryResponse;
import com.erp.backend.dto.order.OrderTotalsResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.SalesOrderApprovalRepository;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.actor;
import static com.erp.backend.service.CustomerTestData.customer;
import static com.erp.backend.service.CustomerTestData.salesRep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Test cho Story S5-01 / SCRUM-160:
 * Là Đại lý, tôi muốn theo dõi trạng thái các đơn hàng của mình, để biết khi nào hàng tới.
 * - Danh sách đơn kèm trạng thái, ngày giao dự kiến, tổng tiền.
 * - Xem chi tiết dòng hàng và số lượng thực giao nếu giao thiếu.
 * - Chỉ thấy đơn của chính mình.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class AgencyOrderTrackingServiceTest {

    @Mock private SalesOrderRepository orderRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SalesOrderApprovalRepository approvalRepository;
    @Mock private InventoryService inventoryService;
    @Mock private AuditLogService auditLogService;
    @Mock private OrderDraftService orderDraftService;

    @InjectMocks private OrderQueryService orderQueryService;
    @InjectMocks private OrderStatusService orderStatusService;

    private UserDetailsImpl agencyUser;
    private UserDetailsImpl otherAgencyUser;
    private Customer myCustomer;
    private Customer otherCustomer;
    private SalesOrder myOrder;
    private SalesOrderLine line1;
    private SalesOrderLine line2;

    @BeforeEach
    void setUp() {
        agencyUser = actor(10, "ROLE_CUSTOMER");
        otherAgencyUser = actor(20, "ROLE_CUSTOMER");

        myCustomer = customer(100L, salesRep(5L));
        myCustomer.setCode("DL-MY-AGENCY");
        myCustomer.setName("Đại Lý Của Tôi");
        myCustomer.setEmail("agency@daily.com");

        otherCustomer = customer(200L, salesRep(5L));
        otherCustomer.setCode("DL-OTHER");
        otherCustomer.setName("Đại Lý Khác");
        otherCustomer.setEmail("other@daily.com");

        line1 = SalesOrderLine.builder()
                .id(1L)
                .lineNo(1)
                .productSku("SP-001")
                .productName("Sản phẩm 1")
                .unitName("Thùng")
                .quantity(new BigDecimal("10"))
                .unitPrice(new BigDecimal("100000"))
                .grossAmount(new BigDecimal("1000000"))
                .discountAmount(BigDecimal.ZERO)
                .netAmount(new BigDecimal("1000000"))
                .build();

        line2 = SalesOrderLine.builder()
                .id(2L)
                .lineNo(2)
                .productSku("SP-002")
                .productName("Sản phẩm 2")
                .unitName("Lon")
                .quantity(new BigDecimal("50"))
                .unitPrice(new BigDecimal("10000"))
                .grossAmount(new BigDecimal("500000"))
                .discountAmount(BigDecimal.ZERO)
                .netAmount(new BigDecimal("500000"))
                .build();

        List<SalesOrderLine> lines = new ArrayList<>();
        lines.add(line1);
        lines.add(line2);

        myOrder = SalesOrder.builder()
                .id(500L)
                .code("DH2610-00500")
                .customer(myCustomer)
                .status(SalesOrder.STATUS_DISPATCHED)
                .desiredDeliveryDate(LocalDate.now().plusDays(2))
                .totalAmount(new BigDecimal("1500000"))
                .lines(lines)
                .build();
        line1.setOrder(myOrder);
        line2.setOrder(myOrder);
    }

    @Test
    @DisplayName("S5-01 AC3: Đại lý chỉ xem danh sách đơn hàng của chính mình")
    void search_agencyOnlySeesOwnOrders() {
        when(customerRepository.findByUser_Id(agencyUser.getId())).thenReturn(Optional.of(myCustomer));
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(myOrder)));

        OrderSearchCriteria criteria = new OrderSearchCriteria(null, null, null, null, null, null, null);
        PageResponse<OrderSummaryResponse> result = orderQueryService.search(criteria, 0, 20, agencyUser);

        assertThat(result.content()).hasSize(1);
        OrderSummaryResponse summary = result.content().get(0);
        assertThat(summary.customerId()).isEqualTo(100L);
        assertThat(summary.customerCode()).isEqualTo("DL-MY-AGENCY");
        assertThat(summary.desiredDeliveryDate()).isEqualTo(myOrder.getDesiredDeliveryDate());
        assertThat(summary.totalAmount()).isEqualByComparingTo("1500000");
    }

    @Test
    @DisplayName("S5-01 AC3: Đại lý chưa liên kết tài khoản thì trả về danh sách rỗng")
    void search_unlinkedAgencyReturnsEmptyPage() {
        when(customerRepository.findByUser_Id(agencyUser.getId())).thenReturn(Optional.empty());
        when(customerRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(customerRepository.findByCodeIgnoreCase(any())).thenReturn(Optional.empty());

        OrderSearchCriteria criteria = new OrderSearchCriteria(null, null, null, null, null, null, null);
        PageResponse<OrderSummaryResponse> result = orderQueryService.search(criteria, 0, 20, agencyUser);

        assertThat(result.content()).isEmpty();
        verify(orderRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("S5-01 AC3: Đại lý chỉ tính tổng tiền đơn của chính mình")
    void totals_agencyOnlySumsOwnOrders() {
        when(customerRepository.findByUser_Id(agencyUser.getId())).thenReturn(Optional.of(myCustomer));
        when(orderRepository.sumTotals(any(Specification.class)))
                .thenReturn(new OrderTotalsResponse(3, new BigDecimal("4500000")));

        OrderTotalsResponse totals = orderQueryService.totals(null, agencyUser);

        assertThat(totals.orderCount()).isEqualTo(3);
        assertThat(totals.totalAmount()).isEqualByComparingTo("4500000");
    }

    @Test
    @DisplayName("S5-01 AC2: Ghi nhận số lượng thực giao và phát hiện giao thiếu khi chuyển sang DELIVERED")
    void transitionStatus_deliveredWithShortage() {
        when(orderRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(myOrder));
        when(customerRepository.findByUser_Id(agencyUser.getId())).thenReturn(Optional.of(myCustomer));
        when(orderRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        // Dòng 1: đặt 10 thùng, thực giao 8 thùng (thiếu 2 thùng do vỡ hàng)
        // Dòng 2: đặt 50 lon, thực giao 50 lon (giao đủ)
        List<OrderLineDeliveryRequest> deliveries = List.of(
                new OrderLineDeliveryRequest(1L, new BigDecimal("8"), "Bị vỡ 2 thùng trong lúc vận chuyển"),
                new OrderLineDeliveryRequest(2L, new BigDecimal("50"), null)
        );

        orderStatusService.transitionStatus(500L, SalesOrder.STATUS_DELIVERED, "Giao tại kho đại lý", deliveries, agencyUser);

        assertThat(myOrder.getStatus()).isEqualTo(SalesOrder.STATUS_DELIVERED);
        assertThat(line1.getDeliveredQuantity()).isEqualByComparingTo("8");
        assertThat(line1.getShortageReason()).isEqualTo("Bị vỡ 2 thùng trong lúc vận chuyển");
        assertThat(line2.getDeliveredQuantity()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("S5-01 AC2: Cập nhật số lượng thực giao riêng lẻ qua updateDeliveredQuantities")
    void updateDeliveredQuantities_success() {
        when(orderRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(myOrder));
        when(customerRepository.findByUser_Id(agencyUser.getId())).thenReturn(Optional.of(myCustomer));
        when(orderRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        List<OrderLineDeliveryRequest> deliveries = List.of(
                new OrderLineDeliveryRequest(1L, new BigDecimal("7"), "Kho tạm hết tồn"),
                new OrderLineDeliveryRequest(2L, new BigDecimal("45"), "Thiếu 5 lon")
        );

        orderStatusService.updateDeliveredQuantities(500L, deliveries, agencyUser);

        assertThat(line1.getDeliveredQuantity()).isEqualByComparingTo("7");
        assertThat(line1.getShortageReason()).isEqualTo("Kho tạm hết tồn");
        assertThat(line2.getDeliveredQuantity()).isEqualByComparingTo("45");
        assertThat(line2.getShortageReason()).isEqualTo("Thiếu 5 lon");
    }

    @Test
    @DisplayName("S5-01 AC3: Đại lý khác cố tình truy cập đơn không thuộc về mình -> 404 NOT_FOUND")
    void accessOtherAgencyOrder_throwsNotFound() {
        when(orderRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(myOrder));
        when(customerRepository.findByUser_Id(otherAgencyUser.getId())).thenReturn(Optional.of(otherCustomer));

        assertThatThrownBy(() -> orderStatusService.cancel(500L, "Huỷ đơn trộm", otherAgencyUser))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getStatus().value()).isEqualTo(404);
                    assertThat(be.getCode()).isEqualTo("CUSTOMER_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("S5-01: Summary có gắn cờ hasShortage khi phát hiện đơn có dòng giao thiếu")
    void summary_hasShortageFlag() {
        line1.setDeliveredQuantity(new BigDecimal("8")); // Đặt 10, giao 8 -> thiếu
        line2.setDeliveredQuantity(new BigDecimal("50")); // Đặt 50, giao 50 -> đủ

        OrderSummaryResponse res = OrderQueryService.toSummary(myOrder);

        assertThat(res.hasShortage()).isTrue();
    }
}
