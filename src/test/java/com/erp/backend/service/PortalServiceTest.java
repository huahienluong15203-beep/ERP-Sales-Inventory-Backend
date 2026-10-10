package com.erp.backend.service;

import com.erp.backend.dto.order.*;
import com.erp.backend.dto.portal.PortalOrderLineRequest;
import com.erp.backend.dto.portal.PortalOrderRequest;
import com.erp.backend.dto.portal.PortalOrderResponse;
import com.erp.backend.dto.portal.PortalProductResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.SalesOrder;
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

@ExtendWith(MockitoExtension.class)
class PortalServiceTest {

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

    @BeforeEach
    void setUp() {
        customer = customer(6, salesRep(7));
        lenient().when(customerRepository.findByPortalUser_Id(50L)).thenReturn(Optional.of(customer));
    }

    private static OrderResponse response(Long id, String status, Long customerId) {
        OrderLineResponse line = mock(OrderLineResponse.class);
        lenient().when(line.productSku()).thenReturn("SP-COCA");
        OrderResponse r = mock(OrderResponse.class);
        lenient().when(r.id()).thenReturn(id);
        lenient().when(r.status()).thenReturn(status);
        lenient().when(r.customerId()).thenReturn(customerId);
        lenient().when(r.lines()).thenReturn(List.of(line));
        lenient().when(r.warnings()).thenReturn(List.of("Đơn hàng có 1 mặt hàng bán dưới giá sàn quy định.", "Công nợ sau đơn vượt hạn mức"));
        return r;
    }

    private static PortalOrderRequest cart(String sku, String qty) {
        PortalOrderLineRequest l = new PortalOrderLineRequest();
        l.setProductSku(sku);
        l.setUnitName("Thùng");
        l.setQuantity(new BigDecimal(qty));
        PortalOrderRequest r = new PortalOrderRequest();
        r.setNote("Giao buổi sáng");
        r.setLines(List.of(l));
        return r;
    }

    @Test
    @DisplayName("S4-10: Tài khoản chưa gắn đại lý -> 409 báo liên hệ quản trị viên")
    void notLinked_conflict() {
        when(customerRepository.findByPortalUser_Id(51L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.me(actor(51, "ROLE_CUSTOMER")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "PORTAL_ACCOUNT_NOT_LINKED")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("S4-10: Đặt đơn -> đại lý lấy theo tài khoản, giá không lấy từ client, đơn đánh dấu PORTAL rồi gửi duyệt")
    void placeOrder_forcesCustomerAndPortalSource() {
        SalesOrder created = SalesOrder.builder().id(100L).code("DH261010-AAAA").customer(customer)
                .status(SalesOrder.STATUS_DRAFT).build();
        // Tạo sẵn mock phản hồi trước khi stub (không tạo mock giữa chừng một lệnh when)
        OrderResponse draft = response(100L, "DRAFT", 6L);
        OrderResponse submitted = response(100L, SalesOrder.STATUS_PENDING_APPROVAL, 6L);
        when(orderDraftService.createDraft(any(OrderDraftRequest.class), eq(agent))).thenReturn(draft);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(created));
        when(orderApprovalService.submit(100L, agent)).thenAnswer(inv -> {
            assertThat(created.getSource()).isEqualTo(SalesOrder.SOURCE_PORTAL);
            return submitted;
        });

        PortalOrderResponse res = service.placeOrder(cart("SP-COCA", "5"), agent);

        ArgumentCaptor<OrderDraftRequest> req = ArgumentCaptor.forClass(OrderDraftRequest.class);
        verify(orderDraftService).createDraft(req.capture(), eq(agent));
        assertThat(req.getValue().getCustomerId()).isEqualTo(6L);
        assertThat(req.getValue().getNote()).startsWith("[Đại lý tự đặt qua cổng]").endsWith("Giao buổi sáng");
        assertThat(req.getValue().getLines().get(0).getUnitPrice()).isNull();
        assertThat(req.getValue().getLines().get(0).getIsCustomPrice()).isFalse();
        assertThat(res.status()).isEqualTo(SalesOrder.STATUS_PENDING_APPROVAL);
        assertThat(res.statusLabel()).isEqualTo("Chờ xác nhận");
        // Cảnh báo giá sàn là thông tin nội bộ, không hiện cho đại lý
        assertThat(res.warnings()).containsExactly("Công nợ sau đơn vượt hạn mức");
    }

    @Test
    @DisplayName("S4-10: Giỏ hàng trống -> 400, không tạo đơn")
    void emptyCart_badRequest() {
        PortalOrderRequest empty = new PortalOrderRequest();

        assertThatThrownBy(() -> service.placeOrder(empty, agent))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_EMPTY");
        verifyNoInteractions(orderDraftService, orderApprovalService);
    }

    @Test
    @DisplayName("S4-10: Ghi chú quá dài -> 400")
    void longNote_badRequest() {
        PortalOrderRequest r = cart("SP-COCA", "1");
        r.setNote("a".repeat(401));

        assertThatThrownBy(() -> service.preview(r, agent))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "NOTE_TOO_LONG");
    }

    @Test
    @DisplayName("S4-10: Xem đơn của đại lý khác -> 404")
    void otherCustomersOrder_hidden() {
        Customer other = customer(9, salesRep(7));
        when(orderRepository.findById(200L)).thenReturn(Optional.of(
                SalesOrder.builder().id(200L).customer(other).build()));

        assertThatThrownBy(() -> service.order(200L, agent))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
        verify(orderDraftService, never()).getById(any(), any());
    }

    @Test
    @DisplayName("S4-10: Sản phẩm trên cổng chỉ có giá bán theo nhóm khách, không có giá sàn / tồn nội bộ")
    void products_hideInternalPrices() {
        when(orderDraftService.productOptions(6L, "coca", agent)).thenReturn(List.of(new ProductOptionResponse(
                10L, "SP-COCA", "Coca", "Lon", List.of(new ProductOptionResponse.UnitOption("Thùng", new BigDecimal("24"))),
                true, new BigDecimal("10000"), new BigDecimal("9000"), "BG-C1", null,
                "WH-MB01", "Kho", new BigDecimal("1000"), new BigDecimal("50"), new BigDecimal("950"))));

        List<PortalProductResponse> res = service.products("coca", agent);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).unitPrice()).isEqualByComparingTo("10000");
        assertThat(res.get(0).availableStock()).isEqualByComparingTo("950");
        assertThat(res.get(0).units()).extracting(PortalProductResponse.Unit::unitName).containsExactly("Thùng");
    }

    @Test
    @DisplayName("S4-10: Lịch sử đơn luôn lọc theo đại lý của tài khoản")
    void orders_filteredByOwnCustomer() {
        service.orders(null, 0, 20, agent);

        ArgumentCaptor<OrderSearchCriteria> c = ArgumentCaptor.forClass(OrderSearchCriteria.class);
        verify(orderQueryService).search(c.capture(), eq(0), eq(20), eq(agent));
        assertThat(c.getValue().customerId()).isEqualTo(6L);
    }
}
