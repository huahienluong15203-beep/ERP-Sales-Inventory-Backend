package com.erp.backend.service;

import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.dto.order.ApprovalReason;
import com.erp.backend.dto.order.OrderApprovalHistoryResponse;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderApprovalServiceTest {

    @Mock private SalesOrderRepository orderRepository;
    @Mock private SalesOrderApprovalRepository approvalRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private OrderDraftService orderDraftService;
    @Mock private CustomerCreditService creditService;
    @Mock private AuditLogService auditLogService;
    @Mock private InventoryService inventoryService;

    @InjectMocks private OrderApprovalService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private final UserDetailsImpl rep = actor(7, "ROLE_SALES_REP");
    private final UserDetailsImpl manager = actor(3, "ROLE_SALES_MANAGER");
    private Customer customer;
    private SalesOrder order;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(TODAY.atStartOfDay(OrderApprovalService.VN_ZONE).plusHours(10).toInstant(),
                OrderApprovalService.VN_ZONE);
        customer = customer(6, salesRep(7));
        order = SalesOrder.builder().id(100L).code("DH261008-AAAA").customer(customer)
                .status(SalesOrder.STATUS_DRAFT).totalAmount(new BigDecimal("500000")).build();
        order.addLine(line(new BigDecimal("10000"), new BigDecimal("9000"), "50", "500000"));
        lenient().when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        lenient().when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static SalesOrderLine line(BigDecimal unitPrice, BigDecimal floor, String baseQty, String net) {
        return SalesOrderLine.builder().lineNo(1).productSku("SP-COCA").unitName("Lon")
                .conversionFactor(BigDecimal.ONE).quantity(new BigDecimal(baseQty)).baseUnit("Lon")
                .baseQuantity(new BigDecimal(baseQty)).unitPrice(unitPrice).floorPrice(floor)
                .grossAmount(new BigDecimal(net)).discountAmount(BigDecimal.ZERO).netAmount(new BigDecimal(net)).build();
    }

    private static CreditStatusResponse credit(boolean exceeds, String limit, String exceeded) {
        return new CreditStatusResponse(6L, "DL006", "Đại lý", new BigDecimal(limit), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, exceeds, new BigDecimal(exceeded), 30, false, 0, BigDecimal.ZERO, 0,
                false, null);
    }

    private SalesOrderApproval savedHistory() {
        ArgumentCaptor<SalesOrderApproval> captor = ArgumentCaptor.forClass(SalesOrderApproval.class);
        verify(approvalRepository).save(captor.capture());
        return captor.getValue();
    }

    private void pending() {
        order.setStatus(SalesOrder.STATUS_PENDING_APPROVAL);
        order.setCreditExceededAmount(new BigDecimal("1500000"));
        order.setCreditExceededPercent(new BigDecimal("15.00"));
    }

    // ======================= CHỐT ĐƠN =======================

    @Test
    @DisplayName("S4-05: Chốt đơn không vượt hạn mức, không dưới giá sàn -> tự duyệt, ghi lịch sử")
    void submit_noViolation_autoApproved() {
        when(creditService.evaluate(eq(customer), any())).thenReturn(credit(false, "10000000", "0"));

        service.submit(100L, rep);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_APPROVED);
        assertThat(order.getApprovedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 10, 0));
        assertThat(order.getApprovedByUsername()).isNull();
        assertThat(order.getSubmittedByUsername()).isEqualTo("user7");
        verify(customerRepository).findByIdForUpdate(6L);
        verify(orderDraftService).recalculateForSubmit(order, rep);
        SalesOrderApproval h = savedHistory();
        assertThat(h.getAction()).isEqualTo(SalesOrderApproval.ACTION_AUTO_APPROVE);
        assertThat(h.getFromStatus()).isEqualTo(SalesOrder.STATUS_DRAFT);
        assertThat(h.getToStatus()).isEqualTo(SalesOrder.STATUS_APPROVED);
    }

    @Test
    @DisplayName("S4-05: Chốt đơn vượt hạn mức -> Chờ duyệt, lưu số tiền và % vượt")
    void submit_overCreditLimit_pending() {
        when(creditService.evaluate(eq(customer), any())).thenReturn(credit(true, "10000000", "1500000"));

        service.submit(100L, rep);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_PENDING_APPROVAL);
        assertThat(order.getApprovedAt()).isNull();
        assertThat(order.getCreditExceededAmount()).isEqualByComparingTo("1500000");
        assertThat(order.getCreditExceededPercent()).isEqualByComparingTo("15");
        List<ApprovalReason> reasons = OrderApprovalReasons.of(order);
        assertThat(reasons).extracting(ApprovalReason::code).containsExactly(ApprovalReason.CREDIT_LIMIT);
        assertThat(reasons.get(0).detail()).contains("1.500.000 ₫").contains("15%");
        SalesOrderApproval h = savedHistory();
        assertThat(h.getAction()).isEqualTo(SalesOrderApproval.ACTION_SUBMIT);
        assertThat(h.getComment()).contains("Vượt hạn mức công nợ");
    }

    @Test
    @DisplayName("S4-05: Dòng hàng bán dưới giá sàn (sau chiết khấu) -> Chờ duyệt, báo số dòng và mức thấp hơn giá sàn")
    void submit_belowFloorPrice_pending() {
        order.getLines().clear();
        // 100 lon, giá sàn 9.000, bán thực tế 8.000/lon -> thiếu 100.000, thấp hơn 11,1%
        order.addLine(line(new BigDecimal("10000"), new BigDecimal("9000"), "100", "800000"));
        when(creditService.evaluate(eq(customer), any())).thenReturn(credit(false, "10000000", "0"));

        service.submit(100L, rep);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_PENDING_APPROVAL);
        assertThat(order.getBelowFloorLineCount()).isEqualTo(1);
        assertThat(order.getBelowFloorAmount()).isEqualByComparingTo("100000");
        assertThat(order.getBelowFloorMaxPercent()).isEqualByComparingTo("11.11");
        assertThat(OrderApprovalReasons.of(order)).extracting(ApprovalReason::code)
                .containsExactly(ApprovalReason.BELOW_FLOOR_PRICE);
    }

    @Test
    @DisplayName("S4-05: Hạn mức = 0 mà vẫn có đơn -> Chờ duyệt, % vi phạm để trống")
    void submit_zeroLimit_percentNull() {
        when(creditService.evaluate(eq(customer), any())).thenReturn(credit(true, "0", "500000"));

        service.submit(100L, rep);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_PENDING_APPROVAL);
        assertThat(order.getCreditExceededPercent()).isNull();
    }

    @Test
    @DisplayName("S4-05: Chỉ chốt được đơn nháp")
    void submit_notDraft_conflict() {
        order.setStatus(SalesOrder.STATUS_APPROVED);

        assertThatThrownBy(() -> service.submit(100L, rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_NOT_DRAFT");
        verify(approvalRepository, never()).save(any());
    }

    @Test
    @DisplayName("S4-05: Đơn chưa có dòng hàng -> không chốt được")
    void submit_empty_badRequest() {
        order.getLines().clear();

        assertThatThrownBy(() -> service.submit(100L, rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_EMPTY");
    }

    @Test
    @DisplayName("S4-05: Đại lý nợ quá hạn / bị khoá lúc chốt -> chặn, không ghi lịch sử")
    void submit_customerBlocked_conflict() {
        doThrow(BusinessException.conflict("CUSTOMER_DEBT_OVERDUE", "Nợ quá hạn", "customerId"))
                .when(orderDraftService).recalculateForSubmit(order, rep);

        assertThatThrownBy(() -> service.submit(100L, rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "CUSTOMER_DEBT_OVERDUE");
        verify(approvalRepository, never()).save(any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("S4-05: NV kinh doanh chốt đơn của đại lý người khác -> 404")
    void submit_otherRepsOrder_hidden() {
        assertThatThrownBy(() -> service.submit(100L, actor(8, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    // ======================= DUYỆT =======================

    @Test
    @DisplayName("S4-05: Duyệt -> Đã duyệt, lưu người duyệt, ghi lịch sử và nhật ký")
    void approve_ok() {
        pending();

        service.approve(100L, "  Khách quen, đồng ý  ", manager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_APPROVED);
        assertThat(order.getApprovedByUsername()).isEqualTo("user3");
        assertThat(order.getApprovedById()).isEqualTo(3L);
        assertThat(order.getApprovedAt()).isNotNull();
        SalesOrderApproval h = savedHistory();
        assertThat(h.getAction()).isEqualTo(SalesOrderApproval.ACTION_APPROVE);
        assertThat(h.getComment()).isEqualTo("Khách quen, đồng ý");
        assertThat(h.getActorFullName()).isEqualTo("Người dùng 3");
        verify(auditLogService).record(eq(AuditModule.INVOICE), eq("APPROVE_ORDER"), eq("SALES_ORDER"), eq(100L),
                eq("DH261008-AAAA"), eq(SalesOrder.STATUS_PENDING_APPROVAL), eq(SalesOrder.STATUS_APPROVED), any(), eq(manager));
    }

    @Test
    @DisplayName("S4-05: Duyệt không cần ý kiến")
    void approve_withoutComment_ok() {
        pending();

        service.approve(100L, null, manager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_APPROVED);
        assertThat(savedHistory().getComment()).isNull();
    }

    @Test
    @DisplayName("S4-05: Đơn không ở trạng thái chờ duyệt (đã có người xử lý) -> 409")
    void approve_notPending_conflict() {
        order.setStatus(SalesOrder.STATUS_APPROVED);

        assertThatThrownBy(() -> service.approve(100L, null, manager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_NOT_PENDING")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("S4-05: Từ chối không nhập ý kiến -> 400, đơn giữ nguyên")
    void reject_withoutComment_badRequest() {
        pending();

        assertThatThrownBy(() -> service.reject(100L, "   ", manager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "COMMENT_REQUIRED");
        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_PENDING_APPROVAL);
        verify(approvalRepository, never()).save(any());
    }

    @Test
    @DisplayName("S4-05: Từ chối có ý kiến -> Từ chối, lưu ý kiến")
    void reject_ok() {
        pending();

        service.reject(100L, "Đại lý đang nợ nhiều", manager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_REJECTED);
        assertThat(order.getLastApprovalComment()).isEqualTo("Đại lý đang nợ nhiều");
        SalesOrderApproval h = savedHistory();
        assertThat(h.getAction()).isEqualTo(SalesOrderApproval.ACTION_REJECT);
        assertThat(h.getToStatus()).isEqualTo(SalesOrder.STATUS_REJECTED);
    }

    @Test
    @DisplayName("S4-05: Trả lại sửa không nhập ý kiến -> 400")
    void return_withoutComment_badRequest() {
        pending();

        assertThatThrownBy(() -> service.returnForEdit(100L, null, manager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "COMMENT_REQUIRED");
    }

    @Test
    @DisplayName("S4-05: Trả lại sửa -> đơn về Nháp kèm ý kiến để NV kinh doanh sửa")
    void return_ok() {
        pending();

        service.returnForEdit(100L, "Giảm số lượng còn 30 thùng", manager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_DRAFT);
        assertThat(order.getLastApprovalComment()).isEqualTo("Giảm số lượng còn 30 thùng");
        assertThat(savedHistory().getAction()).isEqualTo(SalesOrderApproval.ACTION_RETURN);
    }

    @Test
    @DisplayName("S4-05: Ý kiến dài quá 500 ký tự -> 400")
    void comment_tooLong_badRequest() {
        assertThatThrownBy(() -> service.reject(100L, "a".repeat(501), manager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "COMMENT_TOO_LONG");
    }

    // ======================= LỊCH SỬ =======================

    @Test
    @DisplayName("S4-05: Xem lịch sử duyệt theo thứ tự thời gian, có tên hành động")
    void history_ok() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        SalesOrderApproval submit = SalesOrderApproval.builder().id(1L).order(order)
                .action(SalesOrderApproval.ACTION_SUBMIT).fromStatus("DRAFT").toStatus("PENDING_APPROVAL")
                .actorUsername("user7").build();
        SalesOrderApproval approve = SalesOrderApproval.builder().id(2L).order(order)
                .action(SalesOrderApproval.ACTION_APPROVE).fromStatus("PENDING_APPROVAL").toStatus("APPROVED")
                .actorUsername("user3").build();
        when(approvalRepository.findByOrder_IdOrderByCreatedAtAscIdAsc(100L)).thenReturn(List.of(submit, approve));

        List<OrderApprovalHistoryResponse> res = service.history(100L, rep);

        assertThat(res).extracting(OrderApprovalHistoryResponse::actionLabel).containsExactly("Gửi duyệt", "Duyệt");
    }

    @Test
    @DisplayName("S4-05: NV kinh doanh xem lịch sử đơn của đại lý người khác -> 404")
    void history_otherRepsOrder_hidden() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.history(100L, actor(8, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
        verifyNoInteractions(approvalRepository);
    }

    @Test
    @DisplayName("S4-05: Lịch sử duyệt không có hàm sửa / xoá (repository chỉ có save và tìm)")
    void historyRepository_hasNoDelete() {
        assertThat(SalesOrderApprovalRepository.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .noneMatch(n -> n.startsWith("delete"));
    }
}
