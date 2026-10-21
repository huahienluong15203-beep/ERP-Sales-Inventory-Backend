package com.erp.backend.service;

import com.erp.backend.dto.payment.PaymentReceiptResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.PaymentReceipt;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.actor;
import static com.erp.backend.service.CustomerTestData.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** S7-07: Tra cứu phiếu thu phải giới hạn theo người xem (chống đại lý / NV kinh doanh xem chéo). */
@ExtendWith(MockitoExtension.class)
class PaymentReceiptScopeTest {

    @Mock private PaymentReceiptRepository paymentReceiptRepository;
    @Mock private PaymentAllocationRepository paymentAllocationRepository;
    @Mock private PaymentSequenceRepository sequenceRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private CreditFlagService creditFlagService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private PaymentService service;

    /** Chạy Specification đã gửi xuống repository trên Criteria giả, trả về CriteriaBuilder để kiểm điều kiện. */
    @SuppressWarnings("unchecked")
    private CriteriaBuilder runCapturedSpec() {
        ArgumentCaptor<Specification<PaymentReceipt>> spec = ArgumentCaptor.forClass(Specification.class);
        verify(paymentReceiptRepository).findAll(spec.capture(), any(Pageable.class));
        Root<PaymentReceipt> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        spec.getValue().toPredicate(root, query, cb);
        return cb;
    }

    @Test
    @DisplayName("S7-07: Đại lý tra phiếu thu của đại lý khác (customerId=99) -> vẫn chỉ lọc theo đại lý của mình")
    @SuppressWarnings("unchecked")
    void customer_forcedToOwnCustomer() {
        UserDetailsImpl dealer = actor(50, "ROLE_CUSTOMER");
        Customer own = customer(6, null);
        when(customerRepository.findByPortalUser_Id(50L)).thenReturn(Optional.of(own));
        when(paymentReceiptRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.searchReceipts(99L, null, null, null, null, null, 0, 20, dealer);

        CriteriaBuilder cb = runCapturedSpec();
        verify(cb).equal(any(Expression.class), eq(6L));
        verify(cb, never()).equal(any(Expression.class), eq(99L));
    }

    @Test
    @DisplayName("S7-07: Tài khoản đại lý chưa gắn hồ sơ -> danh sách rỗng, không truy vấn")
    void customer_notLinked_empty() {
        UserDetailsImpl dealer = actor(50, "ROLE_CUSTOMER");

        PageResponse<PaymentReceiptResponse> res = service.searchReceipts(null, null, null, null, null, null, 0, 20, dealer);

        assertThat(res.content()).isEmpty();
        verifyNoInteractions(paymentReceiptRepository);
    }

    @Test
    @DisplayName("S7-07: NV kinh doanh chỉ thấy phiếu thu của đại lý mình phụ trách")
    @SuppressWarnings("unchecked")
    void salesRep_filteredBySalesRep() {
        UserDetailsImpl rep = actor(7, "ROLE_SALES_REP");
        when(paymentReceiptRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.searchReceipts(null, null, null, null, null, null, 0, 20, rep);

        CriteriaBuilder cb = runCapturedSpec();
        verify(cb).equal(any(Expression.class), eq(7L));
    }

    @Test
    @DisplayName("S7-07: Kế toán thấy mọi phiếu thu (không bị giới hạn)")
    @SuppressWarnings("unchecked")
    void accountant_unrestricted() {
        UserDetailsImpl acc = actor(3, "ROLE_ACCOUNTANT");
        when(paymentReceiptRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.searchReceipts(null, null, null, null, null, null, 0, 20, acc);

        CriteriaBuilder cb = runCapturedSpec();
        verify(cb, never()).equal(any(Expression.class), any(Object.class));
    }
}
