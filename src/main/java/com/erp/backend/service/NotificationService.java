package com.erp.backend.service;

import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.dto.notification.NotificationResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.Notification;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.NotificationRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * S7-08: Dịch vụ quản lý thông báo chuông trong ứng dụng.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final CustomerRepository customerRepository;

    Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getMyNotifications(int page, int size, UserDetailsImpl actor) {
        Long userId = actor != null ? actor.getId() : null;
        Long customerId = resolveCustomerId(actor);

        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        Pageable pageable = PageRequest.of(p, s);

        Page<Notification> result = notificationRepository.findByUserOrCustomer(userId, customerId, pageable);
        List<NotificationResponse> content = result.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new PageResponse<>(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getTopRecent(UserDetailsImpl actor) {
        Long userId = actor != null ? actor.getId() : null;
        Long customerId = resolveCustomerId(actor);
        return notificationRepository.findTop30ByUserOrCustomer(userId, customerId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(UserDetailsImpl actor) {
        Long userId = actor != null ? actor.getId() : null;
        Long customerId = resolveCustomerId(actor);
        return notificationRepository.countUnread(userId, customerId);
    }

    @Transactional
    public NotificationResponse markAsRead(Long id, UserDetailsImpl actor) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy thông báo"));
        // Chỉ người nhận (theo tài khoản hoặc theo đại lý của tài khoản) mới xem / đánh dấu được; khác -> 404, không lộ nội dung
        if (!isOwner(notification, actor)) {
            throw BusinessException.notFound("Không tìm thấy thông báo");
        }

        if (!notification.isRead()) {
            notification.setRead(true);
            notification.setReadAt(LocalDateTime.now(clock));
            notificationRepository.save(notification);
        }

        return toResponse(notification);
    }

    @Transactional
    public int markAllAsRead(UserDetailsImpl actor) {
        Long userId = actor != null ? actor.getId() : null;
        Long customerId = resolveCustomerId(actor);
        return notificationRepository.markAllAsRead(userId, customerId, LocalDateTime.now(clock));
    }

    /** Thông báo thuộc về người đang đăng nhập: gửi đích danh tài khoản, hoặc gửi cho đại lý mà tài khoản đại diện. */
    boolean isOwner(Notification n, UserDetailsImpl actor) {
        if (actor == null) {
            return false;
        }
        if (n.getUserId() != null && n.getUserId().equals(actor.getId())) {
            return true;
        }
        Long customerId = resolveCustomerId(actor);
        return customerId != null && customerId.equals(n.getCustomerId());
    }

    private Long resolveCustomerId(UserDetailsImpl actor) {
        if (actor == null) return null;
        if (CustomerAccess.isCustomer(actor)) {
            Customer c = CustomerAccess.resolveCustomerForActor(actor, customerRepository);
            return c != null ? c.getId() : null;
        }
        return null;
    }

    private NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getTitle(),
                n.getContent(),
                n.getNotificationType(),
                n.getReferenceType(),
                n.getReferenceId(),
                n.getReferenceCode(),
                n.isRead(),
                n.getReadAt(),
                n.getCreatedAt()
        );
    }
}
