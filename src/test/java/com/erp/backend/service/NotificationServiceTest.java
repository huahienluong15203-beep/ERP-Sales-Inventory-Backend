package com.erp.backend.service;

import com.erp.backend.dto.notification.NotificationResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.Notification;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.NotificationRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** TC708-20: Đánh dấu đã đọc chỉ cho người nhận thông báo. */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private PaymentReminderService paymentReminderService;

    @InjectMocks private NotificationService service;

    private static Notification notif(Long userId, Long customerId) {
        Notification n = new Notification();
        n.setId(1L);
        n.setUserId(userId);
        n.setCustomerId(customerId);
        n.setTitle("Hoá đơn sắp đến hạn");
        return n;
    }

    @Test
    @DisplayName("TC708-20: Đúng tài khoản nhận -> đánh dấu đã đọc")
    void owner_marksRead() {
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(notif(100L, null)));

        NotificationResponse r = service.markAsRead(1L, actor(100, "ROLE_ACCOUNTANT"));

        assertThat(r.isRead()).isTrue();
        verify(notificationRepository).save(any());
    }

    @Test
    @DisplayName("TC708-20: Đại lý đọc thông báo gửi cho đại lý của mình")
    void customerOwner_marksRead() {
        UserDetailsImpl dl = actor(200, "ROLE_CUSTOMER");
        Customer c = new Customer();
        c.setId(5L);
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(notif(null, 5L)));
        when(customerRepository.findByPortalUser_Id(200L)).thenReturn(Optional.of(c));

        assertThat(service.markAsRead(1L, dl).isRead()).isTrue();
    }

    @Test
    @DisplayName("TC708-20: Thông báo của người khác -> 404, không đổi trạng thái")
    void otherUser_notFound() {
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(notif(100L, 5L)));

        assertThatThrownBy(() -> service.markAsRead(1L, actor(101, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC708-20: Đại lý khác đọc thông báo của đại lý 5 -> 404")
    void otherCustomer_notFound() {
        UserDetailsImpl dl = actor(201, "ROLE_CUSTOMER");
        Customer c = new Customer();
        c.setId(6L);
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(notif(null, 5L)));
        when(customerRepository.findByPortalUser_Id(201L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.markAsRead(1L, dl)).isInstanceOf(BusinessException.class);
        verify(notificationRepository, never()).save(any());
    }
}
