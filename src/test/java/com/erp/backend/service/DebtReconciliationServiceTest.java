package com.erp.backend.service;

import com.erp.backend.dto.debt.DebtReconciliationCreateRequest;
import com.erp.backend.dto.debt.DebtReconciliationRespondRequest;
import com.erp.backend.dto.debt.DebtReconciliationResponse;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.DebtReconciliation;
import com.erp.backend.entity.Invoice;
import com.erp.backend.entity.Notification;
import com.erp.backend.entity.PaymentReceipt;
import com.erp.backend.entity.User;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.actor;
import static com.erp.backend.service.CustomerTestData.customer;
import static com.erp.backend.service.CustomerTestData.salesRep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S7-06: Biên bản đối chiếu công nợ định kỳ. */
@ExtendWith(MockitoExtension.class)
class DebtReconciliationServiceTest {

    @Mock private DebtReconciliationRepository reconciliationRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private PaymentReceiptRepository paymentReceiptRepository;
    @Mock private CreditNoteRepository creditNoteRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private AuditLogService auditLogService;

    private DebtReconciliationService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);
    private final UserDetailsImpl accountant = actor(3, "ROLE_ACCOUNTANT");
    private final UserDetailsImpl dealer = actor(100, "ROLE_CUSTOMER");
    private Customer c6;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atTime(9, 0).atZone(VN).toInstant(), VN);
        CustomerDebtStatementService statements = new CustomerDebtStatementService(customerRepository, invoiceRepository,
                paymentReceiptRepository, creditNoteRepository);
        statements.clock = clock;
        service = new DebtReconciliationService(reconciliationRepository, statements, customerRepository,
                notificationRepository, auditLogService);
        service.clock = clock;
        c6 = customer(6, salesRep(7));
        c6.setName("Đại lý Minh An");
        User portal = new User();
        portal.setId(100L);
        c6.setPortalUser(portal);
    }

    private void ledgerData() {
        lenient().when(customerRepository.findById(6L)).thenReturn(Optional.of(c6));
        Invoice before = new Invoice();
        before.setId(1L);
        before.setCode("HD-1");
        before.setInvoiceDate(LocalDate.of(2026, 8, 20));
        before.setTotalAmount(new BigDecimal("2000000"));
        before.setStatus(Invoice.STATUS_ISSUED);
        Invoice in = new Invoice();
        in.setId(2L);
        in.setCode("HD-2");
        in.setInvoiceDate(LocalDate.of(2026, 9, 15));
        in.setTotalAmount(new BigDecimal("3000000"));
        in.setStatus(Invoice.STATUS_ISSUED);
        when(invoiceRepository.findByCustomer_IdAndInvoiceDateLessThanEqual(6L, TO)).thenReturn(List.of(before, in));
        PaymentReceipt p = new PaymentReceipt();
        p.setId(11L);
        p.setCode("PT-1");
        p.setPaymentDate(LocalDate.of(2026, 9, 20));
        p.setAmount(new BigDecimal("1000000"));
        p.setPaymentMethod(PaymentReceipt.METHOD_CASH);
        p.setStatus(PaymentReceipt.STATUS_CONFIRMED);
        when(paymentReceiptRepository.findByCustomer_IdAndStatusAndPaymentDateLessThanEqual(6L,
                PaymentReceipt.STATUS_CONFIRMED, TO)).thenReturn(List.of(p));
        when(creditNoteRepository.findByCustomer_IdAndNoteDateLessThanEqual(6L, TO)).thenReturn(List.of());
    }

    private void saveReturnsArg() {
        when(reconciliationRepository.save(any(DebtReconciliation.class))).thenAnswer(inv -> {
            DebtReconciliation r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(50L);
            }
            return r;
        });
    }

    private static DebtReconciliationCreateRequest req(Boolean draft) {
        return new DebtReconciliationCreateRequest(6L, FROM, TO, LocalDate.of(2026, 10, 1), "Chốt tháng 9", draft);
    }

    private DebtReconciliation existing(String status) {
        DebtReconciliation r = DebtReconciliation.builder().id(50L).code("BB-DC-202610-001").customer(c6)
                .customerCode("DL-6").fromDate(FROM).toDate(TO).statementDate(TODAY)
                .openingBalance(new BigDecimal("2000000")).totalDebit(new BigDecimal("3000000"))
                .totalCredit(new BigDecimal("1000000")).closingBalance(new BigDecimal("4000000")).status(status).build();
        return r;
    }

    @Test
    @DisplayName("S7-06: Lập biên bản: đầu kỳ, phát sinh, cuối kỳ đúng; chốt ngay, gửi thông báo đại lý, ghi nhật ký")
    void create_confirmed() {
        ledgerData();
        saveReturnsArg();
        when(reconciliationRepository.findCodesByPrefix("BB-DC-202610-")).thenReturn(List.of("BB-DC-202610-002"));

        DebtReconciliationResponse r = service.create(req(null), accountant);

        assertThat(r.statementCode()).isEqualTo("BB-DC-202610-003");
        assertThat(r.status()).isEqualTo(DebtReconciliation.STATUS_CONFIRMED);
        assertThat(r.openingBalance()).isEqualByComparingTo("2000000");
        assertThat(r.totalDebit()).isEqualByComparingTo("3000000");
        assertThat(r.totalCredit()).isEqualByComparingTo("1000000");
        assertThat(r.closingBalance()).isEqualByComparingTo("4000000");
        assertThat(r.transactions()).extracting(DebtReconciliationResponse.Transaction::code).containsExactly("HD-2", "PT-1");
        assertThat(r.transactions().get(1).balance()).isEqualByComparingTo("4000000");
        assertThat(r.confirmedBy()).isEqualTo("Người dùng 3");
        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(n.capture());
        assertThat(n.getValue().getUserId()).isEqualTo(100L);
        assertThat(n.getValue().getCustomerId()).isEqualTo(6L);
        assertThat(n.getValue().getReferenceCode()).isEqualTo("BB-DC-202610-003");
        verify(auditLogService).record(eq(AuditModule.INVOICE), eq("RECONCILIATION_CREATE"), eq("DEBT_RECONCILIATION"),
                eq(50L), eq("BB-DC-202610-003"), isNull(), contains("Cuối kỳ: 4000000"), any(), eq(accountant));
    }

    @Test
    @DisplayName("S7-06: Lưu nháp thì chưa gửi đại lý")
    void create_draft_noNotification() {
        ledgerData();
        saveReturnsArg();

        DebtReconciliationResponse r = service.create(req(true), accountant);

        assertThat(r.status()).isEqualTo(DebtReconciliation.STATUS_DRAFT);
        assertThat(r.confirmedAt()).isNull();
        verifyNoInteractions(notificationRepository);
    }

    @Test
    @DisplayName("S7-06: Chỉ Kế toán / Admin lập biên bản -> NV kinh doanh bị 409 (không 403)")
    void create_salesRep_rejected() {
        assertThatThrownBy(() -> service.create(req(null), actor(7, "ROLE_SALES_REP")))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("RECONCILIATION_ACCOUNTANT_ONLY");
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                });
        verifyNoInteractions(reconciliationRepository);
    }

    @Test
    @DisplayName("S7-06: Đã có biên bản cùng kỳ (chưa bị phản hồi lệch) -> 409")
    void create_duplicate_rejected() {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(c6));
        when(reconciliationRepository.existsByCustomer_IdAndFromDateAndToDateAndStatusIn(eq(6L), eq(FROM), eq(TO), anyCollection()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(req(null), accountant))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("RECONCILIATION_DUPLICATE"));
        verify(reconciliationRepository, never()).save(any());
    }

    @Test
    @DisplayName("S7-06: Kỳ chưa kết thúc / ngày lập trước cuối kỳ -> 400")
    void create_invalidDates() {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(c6));

        assertThatThrownBy(() -> service.create(new DebtReconciliationCreateRequest(6L, FROM, TODAY.plusDays(1), null, null, null), accountant))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("PERIOD_IN_FUTURE"));
        assertThatThrownBy(() -> service.create(new DebtReconciliationCreateRequest(6L, FROM, TO, LocalDate.of(2026, 9, 29), null, null), accountant))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("INVALID_STATEMENT_DATE"));
        assertThatThrownBy(() -> service.create(new DebtReconciliationCreateRequest(6L, null, TO, null, null, null), accountant))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("PERIOD_REQUIRED"));
    }

    @Test
    @DisplayName("S7-06: Chốt bản nháp: tính lại số liệu, chuyển chờ đại lý xác nhận; chốt lại lần 2 -> 409")
    void confirm_draft() {
        DebtReconciliation draft = existing(DebtReconciliation.STATUS_DRAFT);
        draft.setOpeningBalance(BigDecimal.ZERO);
        when(reconciliationRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(draft));
        ledgerData();
        saveReturnsArg();

        DebtReconciliationResponse r = service.confirm(50L, accountant);

        assertThat(r.status()).isEqualTo(DebtReconciliation.STATUS_CONFIRMED);
        assertThat(r.openingBalance()).isEqualByComparingTo("2000000");
        verify(notificationRepository).save(any());
        assertThatThrownBy(() -> service.confirm(50L, accountant))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("RECONCILIATION_NOT_DRAFT"));
    }

    @Test
    @DisplayName("S7-06: Đại lý xác nhận trên cổng đại lý -> AGENCY_ACCEPTED, lưu người ký và thời điểm")
    void respond_accept() {
        when(reconciliationRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(existing(DebtReconciliation.STATUS_CONFIRMED)));
        when(customerRepository.findByPortalUser_Id(100L)).thenReturn(Optional.of(c6));
        saveReturnsArg();

        DebtReconciliationResponse r = service.respond(50L,
                new DebtReconciliationRespondRequest("AGENCY_ACCEPTED", null, "Nguyễn Văn A"), dealer);

        assertThat(r.status()).isEqualTo(DebtReconciliation.STATUS_AGENCY_ACCEPTED);
        assertThat(r.agencySignedBy()).isEqualTo("Nguyễn Văn A");
        assertThat(r.agencySignedAt()).isNotNull();
        verify(auditLogService).record(eq(AuditModule.INVOICE), eq("RECONCILIATION_AGENCY_ACCEPT"), any(), eq(50L), any(),
                any(), any(), any(), eq(dealer));
    }

    @Test
    @DisplayName("S7-06: Đại lý phản hồi lệch phải ghi rõ lý do; có lý do -> AGENCY_DISPUTED")
    void respond_dispute() {
        assertThatThrownBy(() -> service.respond(50L, new DebtReconciliationRespondRequest("AGENCY_DISPUTED", " ", null), dealer))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("RECONCILIATION_FEEDBACK_REQUIRED"));

        when(reconciliationRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(existing(DebtReconciliation.STATUS_CONFIRMED)));
        when(customerRepository.findByPortalUser_Id(100L)).thenReturn(Optional.of(c6));
        saveReturnsArg();

        DebtReconciliationResponse r = service.respond(50L,
                new DebtReconciliationRespondRequest("agency_disputed", "Thiếu phiếu thu 500.000 ngày 28/09", null), dealer);

        assertThat(r.status()).isEqualTo(DebtReconciliation.STATUS_AGENCY_DISPUTED);
        assertThat(r.agencyFeedback()).isEqualTo("Thiếu phiếu thu 500.000 ngày 28/09");
        assertThat(r.agencySignedBy()).isEqualTo("Người dùng 100");
    }

    @Test
    @DisplayName("S7-06: Kế toán không được ký thay đại lý -> 409; đại lý khác -> 404; đã phản hồi -> 409")
    void respond_guards() {
        assertThatThrownBy(() -> service.respond(50L, new DebtReconciliationRespondRequest("AGENCY_ACCEPTED", null, null), accountant))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("RECONCILIATION_CUSTOMER_ONLY"));

        UserDetailsImpl other = actor(200, "ROLE_CUSTOMER");
        Customer c9 = customer(9, salesRep(7));
        when(customerRepository.findByPortalUser_Id(200L)).thenReturn(Optional.of(c9));
        when(reconciliationRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(existing(DebtReconciliation.STATUS_CONFIRMED)));
        assertThatThrownBy(() -> service.respond(50L, new DebtReconciliationRespondRequest("AGENCY_ACCEPTED", null, null), other))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(404));

        when(customerRepository.findByPortalUser_Id(100L)).thenReturn(Optional.of(c6));
        when(reconciliationRepository.findByIdForUpdate(51L)).thenReturn(Optional.of(existing(DebtReconciliation.STATUS_AGENCY_ACCEPTED)));
        assertThatThrownBy(() -> service.respond(51L, new DebtReconciliationRespondRequest("AGENCY_DISPUTED", "Sai số liệu", null), dealer))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("RECONCILIATION_ALREADY_RESPONDED"));
        verify(reconciliationRepository, never()).save(any());
    }

    @Test
    @DisplayName("S7-06: Đại lý chỉ thấy biên bản của mình và không thấy bản nháp")
    void list_customerScope() {
        when(customerRepository.findByPortalUser_Id(100L)).thenReturn(Optional.of(c6));
        when(reconciliationRepository.findByCustomer_IdOrderByIdDesc(6L)).thenReturn(List.of(
                existing(DebtReconciliation.STATUS_CONFIRMED), existing(DebtReconciliation.STATUS_DRAFT)));

        List<DebtReconciliationResponse> list = service.list(99L, null, dealer);
        assertThat(list).isEmpty(); // hỏi đại lý khác -> rỗng

        list = service.list(null, "ALL", dealer);
        assertThat(list).extracting(DebtReconciliationResponse::status).containsExactly(DebtReconciliation.STATUS_CONFIRMED);
    }

    @Test
    @DisplayName("S7-06: NV kinh doanh chỉ thấy biên bản của đại lý mình phụ trách")
    void list_salesRepScope() {
        DebtReconciliation mine = existing(DebtReconciliation.STATUS_CONFIRMED);
        DebtReconciliation others = existing(DebtReconciliation.STATUS_CONFIRMED);
        others.setCustomer(customer(9, salesRep(8)));
        when(reconciliationRepository.findTop500ByOrderByIdDesc()).thenReturn(List.of(mine, others));

        assertThat(service.list(null, null, actor(7, "ROLE_SALES_REP"))).hasSize(1);
        assertThat(service.list(null, "CONFIRMED", accountant)).hasSize(2);
        assertThat(service.list(null, "DRAFT", accountant)).isEmpty();
    }

    @Test
    @DisplayName("S7-06: Xem trước số liệu (chưa lưu) để kế toán kiểm tra")
    void preview() {
        ledgerData();

        DebtReconciliationResponse r = service.preview(6L, FROM, TO, null, accountant);

        assertThat(r.id()).isNull();
        assertThat(r.statementDate()).isEqualTo(TODAY);
        assertThat(r.closingBalance()).isEqualByComparingTo("4000000");
        verify(reconciliationRepository, never()).save(any());
    }

    @Test
    @DisplayName("S7-06: In biên bản có đầu kỳ, phát sinh, cuối kỳ bằng chữ, ý kiến lệch; bản nháp có dấu nháp")
    void print() {
        DebtReconciliation r = existing(DebtReconciliation.STATUS_AGENCY_DISPUTED);
        r.setAgencyFeedback("Thiếu phiếu thu");
        when(reconciliationRepository.findById(50L)).thenReturn(Optional.of(r));

        String html = service.printHtml(50L, true, accountant);

        assertThat(html).contains("BIÊN BẢN ĐỐI CHIẾU").contains("BB-DC-202610-001").contains("2.000.000 ₫")
                .contains("4.000.000 ₫").contains("Bốn triệu đồng").contains("Thiếu phiếu thu")
                .contains("PHẢN HỒI LỆCH").contains("window.print()");
        assertThat(DebtReconciliationService.render(DebtReconciliationService.toResponse(
                existing(DebtReconciliation.STATUS_DRAFT)), false)).contains("BẢN NHÁP");
    }
}
