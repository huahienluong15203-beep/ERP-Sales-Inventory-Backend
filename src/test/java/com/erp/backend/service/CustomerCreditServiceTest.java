package com.erp.backend.service;

import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.SalesOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerCreditServiceTest {

    @Mock private SalesOrderRepository orderRepository;
    @Mock private CustomerRepository customerRepository;

    @InjectMocks private CustomerCreditService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private Customer customer;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(TODAY.atStartOfDay(CustomerCreditService.VN_ZONE).plusHours(9).toInstant(),
                CustomerCreditService.VN_ZONE);
        customer = customer(6, salesRep(7));
        customer.setCreditLimit(new BigDecimal("10000000"));
        customer.setMaxDebtDays(30);
    }

    private void debt(String amount) {
        when(orderRepository.sumTotalAmountByCustomerAndStatuses(6L, SalesOrder.OUTSTANDING_DEBT_STATUSES))
                .thenReturn(new BigDecimal(amount));
    }

    private void noOverdue() {
        when(orderRepository.countByCustomer_IdAndStatusInAndApprovedAtBefore(eq(6L), any(), any())).thenReturn(0L);
    }

    @Test
    @DisplayName("S4-02: Hiển thị công nợ hiện tại, hạn mức và còn lại; đơn trong hạn mức -> không cần duyệt")
    void withinLimit_noApprovalNeeded() {
        debt("4000000");
        noOverdue();

        CreditStatusResponse res = service.evaluate(customer, new BigDecimal("5000000"));

        assertThat(res.creditLimit()).isEqualByComparingTo("10000000");
        assertThat(res.currentDebt()).isEqualByComparingTo("4000000");
        assertThat(res.availableCredit()).isEqualByComparingTo("6000000");
        assertThat(res.debtAfterOrder()).isEqualByComparingTo("9000000");
        assertThat(res.exceedsLimit()).isFalse();
        assertThat(res.exceededAmount()).isEqualByComparingTo("0");
        assertThat(res.blocked()).isFalse();
        assertThat(res.message()).isNull();
    }

    @Test
    @DisplayName("S4-02: Tiền đơn + công nợ = đúng hạn mức -> chưa vượt")
    void exactlyAtLimit_notExceeded() {
        debt("4000000");
        noOverdue();

        assertThat(service.evaluate(customer, new BigDecimal("6000000")).exceedsLimit()).isFalse();
    }

    @Test
    @DisplayName("S4-02: Tiền đơn + công nợ > hạn mức -> đơn cần duyệt, báo số tiền vượt")
    void overLimit_needsApproval() {
        debt("8000000");
        noOverdue();

        CreditStatusResponse res = service.evaluate(customer, new BigDecimal("3500000"));

        assertThat(res.exceedsLimit()).isTrue();
        assertThat(res.exceededAmount()).isEqualByComparingTo("1500000");
        assertThat(res.blocked()).isFalse();
        assertThat(res.message()).contains("vượt hạn mức").contains("1.500.000 ₫").contains("cần được duyệt");
    }

    @Test
    @DisplayName("S4-02: Chỉ xem công nợ (không có đơn) -> không bị coi là vượt hạn mức dù đã nợ quá hạn mức")
    void noOrderAmount_notExceeded() {
        debt("12000000");
        noOverdue();

        CreditStatusResponse res = service.evaluate(customer, null);

        assertThat(res.exceedsLimit()).isFalse();
        assertThat(res.availableCredit()).isEqualByComparingTo("-2000000");
    }

    @Test
    @DisplayName("S4-02: Có khoản nợ quá số ngày cho phép -> chặn tạo đơn, báo số ngày quá hạn")
    void overdueDebt_blocked() {
        debt("3000000");
        LocalDateTime dueBefore = TODAY.minusDays(30).atStartOfDay();
        when(orderRepository.countByCustomer_IdAndStatusInAndApprovedAtBefore(6L, SalesOrder.OUTSTANDING_DEBT_STATUSES, dueBefore))
                .thenReturn(2L);
        when(orderRepository.sumOverdueAmount(6L, SalesOrder.OUTSTANDING_DEBT_STATUSES, dueBefore))
                .thenReturn(new BigDecimal("2500000"));
        // duyệt 28/08 + 30 ngày = hạn 27/09 -> đến 08/10 quá hạn 11 ngày
        SalesOrder oldest = SalesOrder.builder().id(1L).approvedAt(LocalDateTime.of(2026, 8, 28, 15, 0)).build();
        when(orderRepository.findFirstByCustomer_IdAndStatusInAndApprovedAtBeforeOrderByApprovedAtAsc(
                6L, SalesOrder.OUTSTANDING_DEBT_STATUSES, dueBefore)).thenReturn(Optional.of(oldest));

        CreditStatusResponse res = service.evaluate(customer, new BigDecimal("100000"));

        assertThat(res.overdue()).isTrue();
        assertThat(res.blocked()).isTrue();
        assertThat(res.overdueOrderCount()).isEqualTo(2);
        assertThat(res.overdueAmount()).isEqualByComparingTo("2500000");
        assertThat(res.overdueDays()).isEqualTo(11);
        assertThat(res.message()).contains("quá hạn 11 ngày").contains("2.500.000 ₫").contains("Chặn tạo đơn");
    }

    @Test
    @DisplayName("S4-02: assertNoOverdueDebt ném 409 CUSTOMER_DEBT_OVERDUE khi có nợ quá hạn")
    void assertNoOverdueDebt_throwsConflict() {
        debt("3000000");
        when(orderRepository.countByCustomer_IdAndStatusInAndApprovedAtBefore(eq(6L), any(), any())).thenReturn(1L);
        when(orderRepository.sumOverdueAmount(eq(6L), any(), any())).thenReturn(new BigDecimal("3000000"));
        when(orderRepository.findFirstByCustomer_IdAndStatusInAndApprovedAtBeforeOrderByApprovedAtAsc(eq(6L), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertNoOverdueDebt(customer))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "CUSTOMER_DEBT_OVERDUE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("S4-02: Không có nợ quá hạn -> assertNoOverdueDebt cho qua")
    void assertNoOverdueDebt_ok() {
        debt("0");
        noOverdue();

        service.assertNoOverdueDebt(customer);
    }

    @Test
    @DisplayName("S4-02: Đại lý chưa đặt số ngày nợ tối đa -> không kiểm tra quá hạn")
    void noMaxDebtDays_skipOverdueCheck() {
        customer.setMaxDebtDays(null);
        debt("0");

        assertThat(service.evaluate(customer, BigDecimal.ONE).overdue()).isFalse();
        verify(orderRepository, never()).countByCustomer_IdAndStatusInAndApprovedAtBefore(any(), any(), any());
    }

    @Test
    @DisplayName("S4-02: API công nợ - tiền đơn âm -> 400")
    void getStatus_negativeAmount_badRequest() {
        assertThatThrownBy(() -> service.getStatus(6L, new BigDecimal("-1"), actor(1, "ROLE_ADMIN")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ORDER_AMOUNT");
    }

    @Test
    @DisplayName("S4-02: API công nợ - NV kinh doanh xem đại lý người khác phụ trách -> 404")
    void getStatus_otherRepsCustomer_hidden() {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service.getStatus(6L, null, actor(8, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("S4-02: API công nợ - NV kinh doanh xem đại lý mình phụ trách -> được")
    void getStatus_ownCustomer_ok() {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(customer));
        debt("1000000");
        noOverdue();

        CreditStatusResponse res = service.getStatus(6L, new BigDecimal("500000"), actor(7, "ROLE_SALES_REP"));

        assertThat(res.customerId()).isEqualTo(6L);
        assertThat(res.availableCredit()).isEqualByComparingTo("9000000");
    }
}
