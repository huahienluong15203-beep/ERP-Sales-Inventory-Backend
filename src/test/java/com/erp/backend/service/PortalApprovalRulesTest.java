package com.erp.backend.service;

import com.erp.backend.dto.order.ApprovalReason;
import com.erp.backend.dto.portal.PortalAccountRequest;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import static com.erp.backend.service.CustomerTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S4-10: Quy tắc xác nhận đơn đại lý tự đặt và gắn tài khoản cổng đại lý. */
@ExtendWith(MockitoExtension.class)
class PortalApprovalRulesTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private PortalAccountService accountService;

    private static SalesOrder order(String source) {
        return SalesOrder.builder().id(1L).code("DH261010-AAAA").source(source)
                .status(SalesOrder.STATUS_PENDING_APPROVAL).build();
    }

    @Test
    @DisplayName("S4-10: Đơn đại lý tự đặt luôn có lý do chờ duyệt 'Đại lý tự đặt'")
    void portalOrder_hasPortalReason() {
        assertThat(OrderApprovalReasons.of(order(SalesOrder.SOURCE_PORTAL)))
                .extracting(ApprovalReason::code).containsExactly(ApprovalReason.PORTAL_ORDER);
        assertThat(OrderApprovalReasons.of(order(SalesOrder.SOURCE_STAFF))).isEmpty();
    }

    @Test
    @DisplayName("S4-10: NV kinh doanh xác nhận được đơn đại lý tự đặt không vi phạm")
    void rep_canConfirmCleanPortalOrder() {
        assertThatCode(() -> OrderApprovalService.assertCanDecide(order(SalesOrder.SOURCE_PORTAL), actor(7, "ROLE_SALES_REP")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("S4-10: Đơn đại lý vượt hạn mức -> NV kinh doanh không được duyệt, cần QL kinh doanh (409)")
    void rep_cannotApproveViolatingPortalOrder() {
        SalesOrder o = order(SalesOrder.SOURCE_PORTAL);
        o.setCreditExceededAmount(new BigDecimal("500000"));

        assertThatThrownBy(() -> OrderApprovalService.assertCanDecide(o, actor(7, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_NEEDS_MANAGER");
        assertThatCode(() -> OrderApprovalService.assertCanDecide(o, actor(3, "ROLE_SALES_MANAGER")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("S4-10: Đơn do nhân viên tạo -> chỉ QL kinh doanh duyệt")
    void rep_cannotApproveStaffOrder() {
        assertThatThrownBy(() -> OrderApprovalService.assertCanDecide(order(SalesOrder.SOURCE_STAFF), actor(7, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_NEEDS_MANAGER");
    }

    private static User customerUser(long id, String status) {
        Role role = Role.builder().id(8L).name(RoleName.ROLE_CUSTOMER).build();
        return User.builder().id(id).username("daily" + id).status(status).roles(Set.of(role)).build();
    }

    private static PortalAccountRequest link(Long userId) {
        PortalAccountRequest r = new PortalAccountRequest();
        r.setUserId(userId);
        return r;
    }

    @Test
    @DisplayName("S4-10: Admin gắn tài khoản Đại lý cho hồ sơ đại lý, ghi nhật ký")
    void link_ok() {
        Customer c = customer(6, salesRep(7));
        when(customerRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(c));
        when(userRepository.findById(50L)).thenReturn(Optional.of(customerUser(50, "ACTIVE")));
        when(customerRepository.findByPortalUser_Id(50L)).thenReturn(Optional.empty());

        accountService.link(6L, link(50L), actor(1, "ROLE_ADMIN"));

        assertThat(c.getPortalUser().getUsername()).isEqualTo("daily50");
        verify(auditLogService).record(eq(AuditModule.CUSTOMER), eq("LINK_PORTAL_ACCOUNT"), eq("CUSTOMER"), eq(6L),
                any(), isNull(), eq("daily50"), any(), any());
    }

    @Test
    @DisplayName("S4-10: Tài khoản không phải vai trò Đại lý -> 400")
    void link_notCustomerRole() {
        when(customerRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(customer(6, salesRep(7))));
        when(userRepository.findById(7L)).thenReturn(Optional.of(salesRep(7)));

        assertThatThrownBy(() -> accountService.link(6L, link(7L), actor(1, "ROLE_ADMIN")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "NOT_CUSTOMER_ACCOUNT");
    }

    @Test
    @DisplayName("S4-10: Tài khoản đã gắn đại lý khác -> 409")
    void link_inUse() {
        when(customerRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(customer(6, salesRep(7))));
        when(userRepository.findById(50L)).thenReturn(Optional.of(customerUser(50, "ACTIVE")));
        when(customerRepository.findByPortalUser_Id(50L)).thenReturn(Optional.of(customer(9, salesRep(7))));

        assertThatThrownBy(() -> accountService.link(6L, link(50L), actor(1, "ROLE_ADMIN")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "PORTAL_ACCOUNT_IN_USE");
        verify(customerRepository, never()).save(any(Customer.class));
    }
}
