package com.erp.backend.service;

import com.erp.backend.dto.order.OrderDraftRequest;
import com.erp.backend.dto.order.OrderLineRequest;
import com.erp.backend.dto.order.OrderResponse;
import com.erp.backend.dto.portal.ReorderPreviewRequest;
import com.erp.backend.dto.portal.ReorderPreviewResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.entity.SalesOrderLine;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S5-02: Đại lý đặt lại đơn cũ — loại hàng ngừng kinh doanh kèm lý do, giá áp lại theo bảng giá hiện hành. */
@ExtendWith(MockitoExtension.class)
class PortalReorderTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private SalesOrderRepository orderRepository;
    @Mock private OrderDraftService orderDraftService;
    @Mock private OrderApprovalService orderApprovalService;
    @Mock private OrderQueryService orderQueryService;
    @Mock private CustomerCreditService creditService;
    @Mock private CustomerDeliveryAddressService deliveryAddressService;

    @InjectMocks private PortalService service;

    private final UserDetailsImpl agent = actor(50, "ROLE_CUSTOMER");
    private Customer customer;
    private SalesOrder old;

    @BeforeEach
    void setUp() {
        customer = customer(6, salesRep(7));
        lenient().when(customerRepository.findByPortalUser_Id(50L)).thenReturn(Optional.of(customer));
        old = SalesOrder.builder().id(300L).code("DH260901-OLD1").customer(customer).status(SalesOrder.STATUS_CLOSED).build();
        old.addLine(line(1L, "SP-COCA", "Thùng", "5"));
        old.addLine(line(2L, "SP-PEPSI", "Thùng", "3"));
        old.addLine(line(3L, "SP-STING", "Lốc", "10"));
        lenient().when(orderRepository.findById(300L)).thenReturn(Optional.of(old));
    }

    private static SalesOrderLine line(Long id, String sku, String unit, String qty) {
        return SalesOrderLine.builder().id(id).productSku(sku).productName(sku).unitName(unit)
                .quantity(new BigDecimal(qty)).unitPrice(new BigDecimal("1000")).build();
    }

    @Test
    @DisplayName("S5-02: Đặt lại toàn bộ: hàng ngừng kinh doanh bị loại kèm lý do, phần còn lại tính giá hiện hành")
    void reorderAll_removesDiscontinued() {
        when(orderDraftService.reorderRejectReason(eq(customer), any(OrderLineRequest.class))).thenAnswer(inv -> {
            OrderLineRequest r = inv.getArgument(1);
            return "SP-PEPSI".equals(r.getProductSku()) ? "sản phẩm 'SP-PEPSI' đã ngừng kinh doanh" : null;
        });
        OrderResponse preview = mock(OrderResponse.class);
        when(orderDraftService.preview(any(OrderDraftRequest.class), eq(agent))).thenReturn(preview);

        ReorderPreviewResponse res = service.reorderPreview(300L, null, agent);

        assertThat(res.keptLines()).extracting(l -> l.getProductSku()).containsExactly("SP-COCA", "SP-STING");
        assertThat(res.removedLines()).hasSize(1);
        assertThat(res.removedLines().get(0).productSku()).isEqualTo("SP-PEPSI");
        assertThat(res.removedLines().get(0).reason()).contains("ngừng kinh doanh");
        assertThat(res.preview()).isNotNull();
        ArgumentCaptor<OrderDraftRequest> req = ArgumentCaptor.forClass(OrderDraftRequest.class);
        verify(orderDraftService).preview(req.capture(), eq(agent));
        assertThat(req.getValue().getCustomerId()).isEqualTo(6L);
        // Không mang giá cũ sang đơn mới
        assertThat(req.getValue().getLines()).allMatch(l -> l.getUnitPrice() == null);
    }

    @Test
    @DisplayName("S5-02: Đặt lại một phần dòng đã chọn")
    void reorderPartial_onlySelectedLines() {
        when(orderDraftService.reorderRejectReason(eq(customer), any(OrderLineRequest.class))).thenReturn(null);
        when(orderDraftService.preview(any(OrderDraftRequest.class), eq(agent))).thenReturn(mock(OrderResponse.class));
        ReorderPreviewRequest req = new ReorderPreviewRequest();
        req.setLineIds(List.of(3L));

        ReorderPreviewResponse res = service.reorderPreview(300L, req, agent);

        assertThat(res.keptLines()).extracting(l -> l.getProductSku()).containsExactly("SP-STING");
        assertThat(res.removedLines()).isEmpty();
    }

    @Test
    @DisplayName("S5-02: Mọi dòng đều không còn đặt được -> trả danh sách bị loại, không tính tiền")
    void allRemoved_noPreview() {
        when(orderDraftService.reorderRejectReason(eq(customer), any(OrderLineRequest.class)))
                .thenReturn("chưa có giá trong bảng giá đang hiệu lực");

        ReorderPreviewResponse res = service.reorderPreview(300L, null, agent);

        assertThat(res.keptLines()).isEmpty();
        assertThat(res.removedLines()).hasSize(3);
        assertThat(res.preview()).isNull();
        verify(orderDraftService, never()).preview(any(), any());
    }

    @Test
    @DisplayName("S5-02: Chọn dòng không thuộc đơn -> 400")
    void unknownLine_badRequest() {
        ReorderPreviewRequest req = new ReorderPreviewRequest();
        req.setLineIds(List.of(99L));

        assertThatThrownBy(() -> service.reorderPreview(300L, req, agent))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ORDER_LINES");
    }

    @Test
    @DisplayName("S5-02: Đặt lại đơn của đại lý khác -> 404")
    void otherCustomersOrder_hidden() {
        when(orderRepository.findById(400L)).thenReturn(Optional.of(
                SalesOrder.builder().id(400L).customer(customer(9, salesRep(7))).build()));

        assertThatThrownBy(() -> service.reorderPreview(400L, null, agent))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
        verifyNoInteractions(orderDraftService);
    }
}
