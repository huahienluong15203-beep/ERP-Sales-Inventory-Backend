package com.erp.backend.repository;

import com.erp.backend.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** S7-08: Kho dữ liệu thông báo trong ứng dụng. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("select n from Notification n where (:userId is not null and n.userId = :userId) or (:customerId is not null and n.customerId = :customerId) order by n.createdAt desc")
    Page<Notification> findByUserOrCustomer(@Param("userId") Long userId, @Param("customerId") Long customerId, Pageable pageable);

    @Query("select n from Notification n where (:userId is not null and n.userId = :userId) or (:customerId is not null and n.customerId = :customerId) order by n.createdAt desc")
    List<Notification> findTop30ByUserOrCustomer(@Param("userId") Long userId, @Param("customerId") Long customerId);

    @Query("select count(n) from Notification n where ((:userId is not null and n.userId = :userId) or (:customerId is not null and n.customerId = :customerId)) and n.isRead = false")
    long countUnread(@Param("userId") Long userId, @Param("customerId") Long customerId);

    @Modifying
    @Query("update Notification n set n.isRead = true, n.readAt = :readAt where ((:userId is not null and n.userId = :userId) or (:customerId is not null and n.customerId = :customerId)) and n.isRead = false")
    int markAllAsRead(@Param("userId") Long userId, @Param("customerId") Long customerId, @Param("readAt") LocalDateTime readAt);

    boolean existsByReferenceTypeAndReferenceIdAndIsReadFalse(String referenceType, Long referenceId);

    List<Notification> findByReferenceTypeAndCustomerIdAndIsReadFalse(String referenceType, Long customerId);
}
