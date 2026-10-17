package com.erp.backend.service;

import com.erp.backend.dto.payment.PaymentReminderRunResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.Invoice;
import com.erp.backend.entity.Notification;
import com.erp.backend.entity.PaymentReminderLog;
import com.erp.backend.entity.User;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.InvoiceRepository;
import com.erp.backend.repository.NotificationRepository;
import com.erp.backend.repository.PaymentReminderLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S7-08: Kiểm thử nhắc hạn thanh toán tự động và chống spam.
 * - Subtask SCRUM-237: Test BE: Giả lập ngày chạy cronjob để test spam.
 * - Subtask SCRUM-262: Cronjob gửi Email/Notify chuông nhắc nợ.
 */
@ExtendWith(MockitoExtension.class)
class PaymentReminderTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private PaymentReminderLogRepository reminderLogRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private PaymentReminderService paymentReminderService;

    private Customer sampleCustomer;
    private Invoice sampleInvoice;

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 10, 16);

    @BeforeEach
    void setUp() {
        lenient().when(reminderLogRepository.tryLockReminderRun()).thenReturn(true);
        User portalUser = new User();
        portalUser.setId(100L);
        portalUser.setUsername("agency_user");

        sampleCustomer = new Customer();
        sampleCustomer.setId(1L);
        sampleCustomer.setCode("CUST001");
        sampleCustomer.setName("Đại lý Miền Bắc");
        sampleCustomer.setEmail("agency@example.com");
        sampleCustomer.setMaxDebtDays(30);
        sampleCustomer.setPortalUser(portalUser);

        sampleInvoice = new Invoice();
        sampleInvoice.setId(10L);
        sampleInvoice.setCode("INV-202610-0001");
        sampleInvoice.setCustomer(sampleCustomer);
        sampleInvoice.setCustomerCode("CUST001");
        sampleInvoice.setCustomerName("Đại lý Miền Bắc");
        sampleInvoice.setInvoiceDate(BASE_DATE.minusDays(27)); // dueDate = BASE_DATE - 27 + 30 = BASE_DATE + 3
        sampleInvoice.setTotalAmount(new BigDecimal("10000000"));
        sampleInvoice.setPaidAmount(new BigDecimal("2000000"));
        sampleInvoice.setRemainingAmount(new BigDecimal("8000000"));
        sampleInvoice.setStatus(Invoice.STATUS_ISSUED);
        sampleInvoice.setPaymentStatus(Invoice.PAYMENT_STATUS_PARTIALLY_PAID);
    }

    @Test
    @DisplayName("SCRUM-237 & 262: Nhắc trước hạn 3 ngày - Tạo thông báo chuông, gửi email và lưu log")
    void testRemindBeforeDue3Days_Success() {
        // Ngày hiện tại: BASE_DATE. Hạn thanh toán: BASE_DATE + 3 ngày (còn đúng 3 ngày)
        when(invoiceRepository.findUnpaidIssuedInvoicesWithCustomer()).thenReturn(List.of(sampleInvoice));
        when(reminderLogRepository.existsByInvoiceIdAndReminderDate(10L, BASE_DATE)).thenReturn(false);

        PaymentReminderRunResponse response = paymentReminderService.scanAndSendReminders(BASE_DATE);

        assertThat(response.scannedCount()).isEqualTo(1);
        assertThat(response.sentBeforeDueCount()).isEqualTo(1);
        assertThat(response.sentOverdueCount()).isEqualTo(0);
        assertThat(response.skippedAlreadySentToday()).isEqualTo(0);
        assertThat(response.errorCount()).isEqualTo(0);

        // Verify In-app notification
        ArgumentCaptor<Notification> notifCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(1)).save(notifCaptor.capture());
        Notification savedNotif = notifCaptor.getValue();
        assertThat(savedNotif.getUserId()).isEqualTo(100L);
        assertThat(savedNotif.getCustomerId()).isEqualTo(1L);
        assertThat(savedNotif.getNotificationType()).isEqualTo(Notification.TYPE_PAYMENT_DUE_SOON);
        assertThat(savedNotif.getReferenceCode()).isEqualTo("INV-202610-0001");
        assertThat(savedNotif.getTitle()).contains("sắp đến hạn thanh toán (còn 3 ngày)");

        // Verify Email
        verify(emailService, times(1)).sendPaymentDueReminderEmail(
                eq("agency@example.com"),
                eq("Đại lý Miền Bắc"),
                eq("INV-202610-0001"),
                eq(new BigDecimal("8000000")),
                eq(BASE_DATE.plusDays(3)),
                eq(3L),
                eq(false)
        );

        // Verify Log
        ArgumentCaptor<PaymentReminderLog> logCaptor = ArgumentCaptor.forClass(PaymentReminderLog.class);
        verify(reminderLogRepository, times(1)).save(logCaptor.capture());
        PaymentReminderLog savedLog = logCaptor.getValue();
        assertThat(savedLog.getReminderType()).isEqualTo(PaymentReminderLog.REMINDER_BEFORE_DUE_3_DAYS);
        assertThat(savedLog.getReminderDate()).isEqualTo(BASE_DATE);
        assertThat(savedLog.getDaysDifference()).isEqualTo(3L);
        assertThat(savedLog.isEmailSent()).isTrue();
        assertThat(savedLog.isInAppSent()).isTrue();
    }

    @Test
    @DisplayName("SCRUM-237: Chống spam - Chạy lại trong cùng ngày sẽ bỏ qua hoá đơn đã gửi")
    void testAntiSpam_SameDayRunAgain_SkipsAlreadySentInvoice() {
        when(invoiceRepository.findUnpaidIssuedInvoicesWithCustomer()).thenReturn(List.of(sampleInvoice));
        // Đã gửi trong ngày BASE_DATE
        when(reminderLogRepository.existsByInvoiceIdAndReminderDate(10L, BASE_DATE)).thenReturn(true);

        PaymentReminderRunResponse response = paymentReminderService.scanAndSendReminders(BASE_DATE);

        assertThat(response.scannedCount()).isEqualTo(1);
        assertThat(response.sentBeforeDueCount()).isEqualTo(0);
        assertThat(response.sentOverdueCount()).isEqualTo(0);
        assertThat(response.skippedAlreadySentToday()).isEqualTo(1);
        assertThat(response.errorCount()).isEqualTo(0);

        // Đảm bảo KHÔNG gửi notification, email hay save log thêm lần nào
        verify(notificationRepository, never()).save(any());
        verify(emailService, never()).sendPaymentDueReminderEmail(any(), any(), any(), any(), any(), anyLong(), anyBoolean());
        verify(reminderLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("SCRUM-237 & 262: Quá hạn thanh toán - Gửi cảnh báo quá hạn")
    void testRemindOverdue_WhenDueDatePassed() {
        // Hoá đơn phát hành 40 ngày trước, hạn 30 ngày -> quá hạn 10 ngày
        sampleInvoice.setInvoiceDate(BASE_DATE.minusDays(40));
        LocalDate dueDate = BASE_DATE.minusDays(10);

        when(invoiceRepository.findUnpaidIssuedInvoicesWithCustomer()).thenReturn(List.of(sampleInvoice));
        when(reminderLogRepository.existsByInvoiceIdAndReminderDate(10L, BASE_DATE)).thenReturn(false);

        PaymentReminderRunResponse response = paymentReminderService.scanAndSendReminders(BASE_DATE);

        assertThat(response.sentOverdueCount()).isEqualTo(1);
        assertThat(response.sentBeforeDueCount()).isEqualTo(0);
        assertThat(response.skippedAlreadySentToday()).isEqualTo(0);

        // Verify In-app notification quá hạn
        ArgumentCaptor<Notification> notifCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(notifCaptor.capture());
        Notification savedNotif = notifCaptor.getValue();
        assertThat(savedNotif.getNotificationType()).isEqualTo(Notification.TYPE_PAYMENT_OVERDUE);
        assertThat(savedNotif.getTitle()).contains("[Cảnh báo quá hạn]");

        // Verify Email quá hạn
        verify(emailService).sendPaymentDueReminderEmail(
                eq("agency@example.com"),
                eq("Đại lý Miền Bắc"),
                eq("INV-202610-0001"),
                eq(new BigDecimal("8000000")),
                eq(dueDate),
                eq(-10L),
                eq(true)
        );

        // Verify Log
        ArgumentCaptor<PaymentReminderLog> logCaptor = ArgumentCaptor.forClass(PaymentReminderLog.class);
        verify(reminderLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getReminderType()).isEqualTo(PaymentReminderLog.REMINDER_OVERDUE);
    }

    @Test
    @DisplayName("SCRUM-237: Giả lập chuyển sang ngày hôm sau - Cho phép gửi nhắc nợ của ngày mới")
    void testSimulateNextDay_AllowsNewReminderForNextDay() {
        // Ngày thứ nhất: BASE_DATE (đã gửi)
        // Ngày thứ hai: BASE_DATE + 1 ngày (ngày mới -> chưa gửi của ngày mới)
        LocalDate nextDay = BASE_DATE.plusDays(1);
        sampleInvoice.setInvoiceDate(BASE_DATE.minusDays(40)); // Quá hạn

        when(invoiceRepository.findUnpaidIssuedInvoicesWithCustomer()).thenReturn(List.of(sampleInvoice));
        when(reminderLogRepository.existsByInvoiceIdAndReminderDate(10L, nextDay)).thenReturn(false);

        PaymentReminderRunResponse response = paymentReminderService.scanAndSendReminders(nextDay);

        assertThat(response.runDate()).isEqualTo(nextDay);
        assertThat(response.sentOverdueCount()).isEqualTo(1);
        assertThat(response.skippedAlreadySentToday()).isEqualTo(0);

        verify(notificationRepository, times(1)).save(any());
        verify(reminderLogRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("Bỏ qua hoá đơn đã thanh toán đủ hoặc nợ còn lại <= 0")
    void testIgnorePaidOrZeroDebtInvoices() {
        sampleInvoice.setRemainingAmount(BigDecimal.ZERO);
        sampleInvoice.setPaymentStatus(Invoice.PAYMENT_STATUS_PAID);

        when(invoiceRepository.findUnpaidIssuedInvoicesWithCustomer()).thenReturn(List.of(sampleInvoice));

        PaymentReminderRunResponse response = paymentReminderService.scanAndSendReminders(BASE_DATE);

        assertThat(response.scannedCount()).isEqualTo(1);
        assertThat(response.sentBeforeDueCount()).isEqualTo(0);
        assertThat(response.sentOverdueCount()).isEqualTo(0);
        assertThat(response.skippedAlreadySentToday()).isEqualTo(0);

        verify(notificationRepository, never()).save(any());
        verify(emailService, never()).sendPaymentDueReminderEmail(any(), any(), any(), any(), any(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("Bỏ qua hoá đơn chưa tới mốc 3 ngày (ví dụ còn 5 ngày hoặc 1 ngày)")
    void testIgnoreInvoiceNotAt3DaysThreshold() {
        // Hoá đơn phát hành 20 ngày trước -> còn 10 ngày mới đến hạn (khác mốc 3 ngày)
        sampleInvoice.setInvoiceDate(BASE_DATE.minusDays(20));

        when(invoiceRepository.findUnpaidIssuedInvoicesWithCustomer()).thenReturn(List.of(sampleInvoice));

        PaymentReminderRunResponse response = paymentReminderService.scanAndSendReminders(BASE_DATE);

        assertThat(response.sentBeforeDueCount()).isEqualTo(0);
        assertThat(response.sentOverdueCount()).isEqualTo(0);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Tính toán ngày đến hạn calculateDueDate() chính xác theo maxDebtDays của Đại lý")
    void testCalculateDueDate() {
        sampleCustomer.setMaxDebtDays(45);
        sampleInvoice.setInvoiceDate(LocalDate.of(2026, 1, 1));

        LocalDate dueDate = sampleInvoice.calculateDueDate();
        assertThat(dueDate).isEqualTo(LocalDate.of(2026, 2, 15));

        // Trường hợp customer null hoặc maxDebtDays null -> mặc định 30 ngày
        sampleCustomer.setMaxDebtDays(null);
        assertThat(sampleInvoice.calculateDueDate()).isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    @DisplayName("TC708-08: Đang có lượt quét khác giữ khoá -> lượt này bỏ qua, không gửi email / thông báo trùng")
    void concurrentRun_skipped() {
        when(reminderLogRepository.tryLockReminderRun()).thenReturn(false);

        PaymentReminderRunResponse response = paymentReminderService.scanAndSendReminders(BASE_DATE);

        assertThat(response.scannedCount()).isZero();
        assertThat(response.sentBeforeDueCount()).isZero();
        verifyNoInteractions(invoiceRepository, emailService, notificationRepository);
        verify(reminderLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC708-08: Lưu nhật ký nhắc hạn trước rồi mới gửi email, gửi thành công thì đánh dấu emailSent")
    void logSavedBeforeEmail() {
        when(invoiceRepository.findUnpaidIssuedInvoicesWithCustomer()).thenReturn(List.of(sampleInvoice));
        when(reminderLogRepository.existsByInvoiceIdAndReminderDate(10L, BASE_DATE)).thenReturn(false);
        ArgumentCaptor<PaymentReminderLog> logCaptor = ArgumentCaptor.forClass(PaymentReminderLog.class);

        paymentReminderService.scanAndSendReminders(BASE_DATE);

        var order = inOrder(reminderLogRepository, emailService);
        order.verify(reminderLogRepository).save(logCaptor.capture());
        order.verify(emailService).sendPaymentDueReminderEmail(any(), any(), any(), any(), any(), anyLong(), anyBoolean());
        assertThat(logCaptor.getValue().isEmailSent()).isTrue();
    }

    @Test
    @DisplayName("TC708-21: NV kinh doanh xem lịch sử nhắc nợ hoá đơn của đại lý người khác phụ trách -> 404")
    void invoiceLogs_otherRepCustomer_notFound() {
        sampleCustomer.setSalesRep(CustomerTestData.salesRep(8L));
        when(invoiceRepository.findById(10L)).thenReturn(Optional.of(sampleInvoice));

        assertThatThrownBy(() -> paymentReminderService.getLogsForInvoice(10L, CustomerTestData.actor(7L, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class);
        verify(reminderLogRepository, never()).findByInvoiceIdOrderBySentAtDesc(anyLong());
    }

    @Test
    @DisplayName("TC708-21: Kế toán xem lịch sử nhắc nợ trả DTO, không trả entity")
    void invoiceLogs_accountant_dto() {
        when(invoiceRepository.findById(10L)).thenReturn(Optional.of(sampleInvoice));
        PaymentReminderLog log = new PaymentReminderLog();
        log.setId(5L);
        log.setInvoiceId(10L);
        log.setInvoiceCode("INV-202610-0001");
        log.setCustomerId(1L);
        log.setCustomerCode("CUST001");
        log.setReminderType(PaymentReminderLog.REMINDER_BEFORE_DUE_3_DAYS);
        log.setReminderDate(BASE_DATE);
        log.setEmailSent(true);
        when(reminderLogRepository.findByInvoiceIdOrderBySentAtDesc(10L)).thenReturn(List.of(log));

        var logs = paymentReminderService.getLogsForInvoice(10L, CustomerTestData.actor(3L, "ROLE_ACCOUNTANT"));

        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).invoiceCode()).isEqualTo("INV-202610-0001");
        assertThat(logs.get(0).customerCode()).isEqualTo("CUST001");
        assertThat(logs.get(0).emailSent()).isTrue();
    }

    @Test
    @DisplayName("TC708-21: Hoá đơn không tồn tại -> 404")
    void invoiceLogs_missing_notFound() {
        when(invoiceRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentReminderService.getLogsForInvoice(99L, CustomerTestData.actor(3L, "ROLE_ACCOUNTANT")))
                .isInstanceOf(BusinessException.class);
    }
}
